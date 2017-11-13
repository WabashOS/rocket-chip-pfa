package freechips.rocketchip.pfa

import chisel3._
import chisel3.util._
import freechips.rocketchip.config.{Field, Parameters}
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.pfa._

// Sends subset of frame in a network packet based on remote memory protocol
// Waits for send completion
class SendPacket(nicaddr: BigInt, name: String)(implicit p: Parameters) extends LazyModule {
  val tlwriter = LazyModule(new TLWriter(name))
  val writenode = TLIdentityNode()
  writenode := tlwriter.node

  val tlreader = LazyModule(new TLReader(name))
  val readnode = TLIdentityNode()
  readnode := tlreader.node

  lazy val module = new SendPacketModule(this, nicaddr)
}

class PacketHeader extends Bundle {
  //val version = UInt(8.W)
  val opcode = UInt(8.W)
  val partid = UInt(8.W)
  val pageid = UInt(32.W)
  val xactid = UInt(16.W)
}

class PacketPayload extends Bundle {
  val addr = UInt(39.W) // addr the nic will read the payload from
  val len = UInt(11.W)  // length of payload
}

class SendPacketReq extends Bundle {
  val header = new PacketHeader
  val payload = new PacketPayload
}

class SendPacketIO extends Bundle {
  val req = Decoupled(new SendPacketReq)
  val resp = Flipped(Decoupled(Bool()))
}

// Writes an address to the respective nic register so the nic takes the data
// and pushes it to the network
class SendPacketModule(outer: SendPacket, nicaddr: BigInt)
    extends LazyModuleImp(outer) {
  val io = IO(new Bundle {
    val sendpacket = Flipped(new SendPacketIO)
    val workbuf = Flipped(Valid(UInt(39.W)))
  })

  val write = outer.tlwriter.module.io.write
  val read = outer.tlreader.module.io.read
  val pktRequest = io.sendpacket.req.bits
  val pktHeader = pktRequest.header
  val pktPayload = pktRequest.payload
  val nicSendReqAddr = nicaddr
  val nicSendCompAddr = nicaddr + 20 // how many completions are available
  val nicSendAckCompAddr = nicaddr + 16 // ack the completions by reading

  // s_header: write header to workbuf
  // s_send1: write workbuf (with header) to nicSendReqAddr
  // s_send2: write payload addr to nicSendReqAddr
  // s_wait: wait for nic completion
  // s_ack: ack completions to nic
  val s_idle :: s_header :: s_send1 :: s_send2 :: s_wait :: s_ack :: s_comp :: Nil = Enum(7)

  val s = RegInit(s_idle)
  // if there's no payload, then we don't use segmented packets, and we just send the header.
  val sendPayload = RegInit(true.B)
  val sendReqFired = RegNext(io.sendpacket.req.fire(), false.B)
  val writeCompFired = RegNext(write.resp.fire(), false.B)
  val readCompFired = RegNext(read.resp.fire(), false.B)
  val nicSentPackets = WireInit((read.resp.bits.data >> 40) & 0xF.U)
  val nicWorkbufReq = WireInit(0.U(64.W))
  val nicPayloadReq = WireInit(Cat(0.U(5.W), pktPayload.len, 0.U(9.W), pktPayload.addr))
  val numAcksSent = Counter(read.resp.fire() && s === s_ack, 2)._1

  nicWorkbufReq := Cat(Mux(sendPayload, 1.U(1.W), 0.U(1.W)), 8.U(15.W), 0.U(9.W), io.workbuf.bits)

  write.req.valid := MuxCase(false.B, Array(
                      (s === s_header) -> sendReqFired,
                      (s === s_send1 || s === s_send2) -> writeCompFired))
  write.req.bits.data := MuxCase(0.U, Array(
                      (s === s_header) -> pktHeader.asUInt,
                      (s === s_send1) -> nicWorkbufReq,
                      (s === s_send2) -> nicPayloadReq))
  write.req.bits.addr := MuxCase(0.U, Array(
                      (s === s_header) -> io.workbuf.bits,
                      (s === s_send1 || s === s_send2) -> nicSendReqAddr.U))
  write.resp.ready := s === s_header || s === s_send1 || s === s_send2

  read.req.valid := MuxCase(false.B, Array(
                      (s === s_wait) -> (writeCompFired || readCompFired),
                      (s === s_ack) -> readCompFired))
  read.req.bits.addr := MuxCase(0.U, Array(
                      (s === s_wait) -> nicSendCompAddr.U,
                      (s === s_ack) -> nicSendAckCompAddr.U))
  read.resp.ready := s === s_wait || s === s_ack

  io.sendpacket.req.ready := s === s_idle && io.workbuf.valid
  io.sendpacket.resp.valid := s === s_comp
  io.sendpacket.resp.bits := true.B

  when (io.sendpacket.req.fire()) {
    s := s_header
    sendPayload := true.B
    numAcksSent := 0.U

    when (pktPayload.len === 0.U) {
      sendPayload := false.B
    }
  }
  when (write.resp.fire()) {
    switch (s) {
      is (s_header) {
        s := s_send1
      }
      is (s_send1) {
        s := Mux(sendPayload, s_send2, s_wait)
      }
      is (s_send2) {
        s := s_wait
      }
    }
  }
  when (read.resp.fire()) {
    switch (s) {
      is (s_wait) {
        when (sendPayload) {
          s := Mux(nicSentPackets > 1.U, s_ack, s_wait) // 2 segments = 2 comps
        } .otherwise {
          s := Mux(nicSentPackets > 0.U, s_ack, s_wait) // 1 segments = 1 comp
        }
      }
      is (s_ack) {
        when (sendPayload) {
          s := Mux(numAcksSent === 1.U, s_comp, s_ack) // do 2 acks
        } .otherwise {
          s := s_comp                                  // 1 ack
        }
      }
    }
  }
  when (io.sendpacket.resp.fire()) {
    s := s_idle
  }
}

class RecvPacketIO extends Bundle {
  val req = Decoupled(new Bundle {
    val taddr = UInt(39.W) // target address
  })
  val resp = Flipped(Decoupled(Bool()))
}

// Writes an address to the recv nic register. The nic takes the data
// coming from network and writes it to the address. Waits for recv completion
class RecvPacket(nicaddr: BigInt, name: String)(implicit p: Parameters) extends LazyModule {
  val tlwriter = LazyModule(new TLWriter(name))
  val writenode = TLIdentityNode()
  writenode := tlwriter.node

  val tlreader = LazyModule(new TLReader(name))
  val readnode = TLIdentityNode()
  readnode := tlreader.node

  lazy val module = new RecvPacketModule(this, nicaddr)
}

class RecvPacketModule(outer: RecvPacket, nicaddr: BigInt) extends LazyModuleImp(outer) {
  val io = IO(new Bundle {
    val recvpacket = Flipped(new RecvPacketIO)
  })

  val write = outer.tlwriter.module.io.write
  val read = outer.tlreader.module.io.read
  val recvTargetAddr = io.recvpacket.req.bits.taddr
  val nicRecvReqAddr = nicaddr + 8
  val nicRecvCompAddr = nicaddr + 20 // the weird comp structure in the nic
  val nicRecvAckCompAddr = nicaddr + 18 // ack the recv completion by reading
  // s_recv: write recv addr to nicRecvReqAddr
  // s_wait: wait for nic completion
  // s_ack: ack completions to nic
  val s_idle :: s_recv :: s_wait :: s_ack :: s_comp :: Nil = Enum(5)

  val s = RegInit(s_idle)
  val recvReqFired = RegNext(io.recvpacket.req.fire(), false.B)
  val writeRespFired = RegNext(write.resp.fire(), false.B)
  val readRespFired = RegNext(read.resp.fire(), false.B)
  val nicRecvPackets = WireInit((read.resp.bits.data >> 44) & 0xF.U)

  io.recvpacket.req.ready := s === s_idle
  io.recvpacket.resp.valid := s === s_comp
  io.recvpacket.resp.bits := true.B

  write.req.valid := s === s_recv && recvReqFired
  write.req.bits.data := recvTargetAddr
  write.req.bits.addr := nicRecvReqAddr.U
  write.resp.ready := s === s_recv

  read.req.valid := MuxCase(false.B, Array(
                (s === s_wait) -> (writeRespFired || readRespFired),
                (s === s_ack) -> readRespFired))
  read.req.bits.addr := Mux(s === s_ack, nicRecvAckCompAddr.U, nicRecvCompAddr.U)
  read.resp.ready := s === s_wait || s === s_ack

  when (io.recvpacket.req.fire()) {
    s := s_recv
  }

  when (write.resp.fire()) {
    s := s_wait
  }

  when (read.resp.fire()) {
    switch (s) {
      is (s_wait) {
        s := Mux(nicRecvPackets > 0.U, s_ack, s_wait)
      }
      is (s_ack) {
        s := s_comp
      }
    }
  }

  when (io.recvpacket.resp.fire()) {
    s := s_idle
  }
}
