// See LICENSE.SiFive for license details.

package bus.axi4

import chisel3._
import chisel3.util._

import eulacore.HasEulaCoreParameter
import utils._

object AXI4Parameters extends HasEulaCoreParameter {
  // These are all fixed by the AXI4 standard:
  val lenBits   = 8
  val sizeBits  = 3
  val burstBits = 2
  val cacheBits = 4
  val protBits  = 3
  val qosBits   = 4
  val respBits  = 2

  // These are not fixed:
  val idBits    = 1
  val addrBits  = PAddrBits
  val dataBits  = DataBits
  val userBits  = 1

  def CACHE_RALLOCATE  = 8.U(cacheBits.W)
  def CACHE_WALLOCATE  = 4.U(cacheBits.W)
  def CACHE_MODIFIABLE = 2.U(cacheBits.W)
  def CACHE_BUFFERABLE = 1.U(cacheBits.W)

  def PROT_PRIVILEDGED = 1.U(protBits.W)
  def PROT_INSECURE    = 2.U(protBits.W)
  def PROT_INSTRUCTION = 4.U(protBits.W)

  def BURST_FIXED = 0.U(burstBits.W)
  def BURST_INCR  = 1.U(burstBits.W)
  def BURST_WRAP  = 2.U(burstBits.W)

  def RESP_OKAY   = 0.U(respBits.W)
  def RESP_EXOKAY = 1.U(respBits.W)
  def RESP_SLVERR = 2.U(respBits.W)
  def RESP_DECERR = 3.U(respBits.W)
}

trait AXI4HasUser {
  val user  = Output(UInt(AXI4Parameters.userBits.W))
}

trait AXI4HasData {
  def dataBits = AXI4Parameters.dataBits
  val data  = Output(UInt(dataBits.W))
}

trait AXI4HasId {
  def idBits = AXI4Parameters.idBits
  val id    = Output(UInt(idBits.W))
}

trait AXI4HasLast {
  val last = Output(Bool())
}

// AXI4-lite

class AXI4LiteBundleA extends Bundle {
  val addr  = Output(UInt(AXI4Parameters.addrBits.W))
  val prot  = Output(UInt(AXI4Parameters.protBits.W))
}

class AXI4LiteBundleW(override val dataBits: Int = AXI4Parameters.dataBits) extends Bundle with AXI4HasData {
  val strb = Output(UInt((dataBits/8).W))
}

class AXI4LiteBundleB extends Bundle {
  val resp = Output(UInt(AXI4Parameters.respBits.W))
}

class AXI4LiteBundleR(override val dataBits: Int = AXI4Parameters.dataBits) extends AXI4LiteBundleB with AXI4HasData


class AXI4Lite extends Bundle {
  val aw = Decoupled(new AXI4LiteBundleA)
  val w  = Decoupled(new AXI4LiteBundleW)
  val b  = Flipped(Decoupled(new AXI4LiteBundleB))
  val ar = Decoupled(new AXI4LiteBundleA)
  val r  = Flipped(Decoupled(new AXI4LiteBundleR))
}


// AXI4-full

class AXI4BundleA(override val idBits: Int) extends AXI4LiteBundleA with AXI4HasId with AXI4HasUser {
  val len   = Output(UInt(AXI4Parameters.lenBits.W))  // number of beats - 1
  val size  = Output(UInt(AXI4Parameters.sizeBits.W)) // bytes in beat = 2^size
  val burst = Output(UInt(AXI4Parameters.burstBits.W))
  val lock  = Output(Bool())
  val cache = Output(UInt(AXI4Parameters.cacheBits.W))
  val qos   = Output(UInt(AXI4Parameters.qosBits.W))  // 0=no QoS, bigger = higher priority
  // val region = UInt(width = 4) // optional

  override def toPrintable: Printable = p"addr = 0x${Hexadecimal(addr)}, id = ${id}, len = ${len}, size = ${size}"
}

// id ... removed in AXI4
class AXI4BundleW(override val dataBits: Int) extends AXI4LiteBundleW(dataBits) with AXI4HasLast {
  override def toPrintable: Printable = p"data = ${Hexadecimal(data)}, wmask = 0x${strb}, last = ${last}"
}
class AXI4BundleB(override val idBits: Int) extends AXI4LiteBundleB with AXI4HasId with AXI4HasUser {
  override def toPrintable: Printable = p"resp = ${resp}, id = ${id}"
}
class AXI4BundleR(override val dataBits: Int, override val idBits: Int) extends AXI4LiteBundleR(dataBits) with AXI4HasLast with AXI4HasId with AXI4HasUser {
  override def toPrintable: Printable = p"resp = ${resp}, id = ${id}, data = ${Hexadecimal(data)}, last = ${last}"
}


class AXI4(val dataBits: Int = AXI4Parameters.dataBits, val idBits: Int = AXI4Parameters.idBits) extends AXI4Lite {
  override val aw = Decoupled(new AXI4BundleA(idBits))
  override val w  = Decoupled(new AXI4BundleW(dataBits))
  override val b  = Flipped(Decoupled(new AXI4BundleB(idBits)))
  override val ar = Decoupled(new AXI4BundleA(idBits))
  override val r  = Flipped(Decoupled(new AXI4BundleR(dataBits, idBits)))

  def dump(name: String) = {
    when (aw.fire()) { printf(p"${GTimer()},[${name}.aw] ${aw.bits}\n") }
    when (w.fire()) { printf(p"${GTimer()},[${name}.w] ${w.bits}\n") }
    when (b.fire()) { printf(p"${GTimer()},[${name}.b] ${b.bits}\n") }
    when (ar.fire()) { printf(p"${GTimer()},[${name}.ar] ${ar.bits}\n") }
    when (r.fire()) { printf(p"${GTimer()},[${name}.r] ${r.bits}\n") }
  }
}

class AXI4XBar1toN(addressSpace: List[(Long, Long)]) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(new AXI4)
    val out = Vec(addressSpace.length, new AXI4)
  })

  val s_idle :: s_resp :: s_error :: Nil = Enum(3)
  val r_state = RegInit(s_idle)
  val w_state = RegInit(s_idle)

  val raddr = io.in.ar.bits.addr
  val routSelVec = VecInit(
    addressSpace.map(range =>
      (raddr >= range._1.U && raddr < (range._1 + range._2).U)
    )
  )
  val routSelIdx = PriorityEncoder(routSelVec)
  val routSel = io.out(routSelIdx)
  val routSelIdxResp =
    RegEnable(routSelIdx, routSel.ar.fire() && (r_state === s_idle))
  val routSelResp = io.out(routSelIdxResp)
  val rreqInvalidAddr = io.in.ar.valid && !routSelVec.asUInt.orR

  // bind out.req channel
  (io.out zip routSelVec).map {
    case (o, v) => {
      o.ar.bits := io.in.ar.bits
      o.ar.valid := v && (io.in.ar.valid && (r_state === s_idle))
      o.r.ready := v
    }
  }
  for (i <- 0 until addressSpace.length) {
    when(routSelIdx === i.U) {
      io.out(i).ar.bits.addr := io.in.ar.bits.addr - addressSpace(i)._1.U
    }
  }

  switch(r_state) {
    is(s_idle) {
      when(routSel.ar.fire()) { r_state := s_resp }
      when(rreqInvalidAddr) { r_state := s_error }
    }
    is(s_resp) { when(routSelResp.r.bits.last) { r_state := s_idle } }
    is(s_error) { when(io.in.r.fire()) { r_state := s_idle } }
  }

  io.in.r.valid := routSelResp.r.valid || r_state === s_error
  io.in.r.bits <> routSelResp.r.bits
  routSelResp.r.ready := io.in.r.ready
  io.in.ar.ready := (routSel.ar.ready && r_state === s_idle) || rreqInvalidAddr

  val waddr = io.in.aw.bits.addr
  val woutSelVec = VecInit(
    addressSpace.map(range =>
      (waddr >= range._1.U && waddr < (range._1 + range._2).U)
    )
  )
  val woutSelIdx = PriorityEncoder(woutSelVec)
  val woutSel = io.out(woutSelIdx)
  val woutSelIdxResp =
    RegEnable(woutSelIdx, woutSel.aw.fire() && (w_state === s_idle))
  val woutSelResp = io.out(woutSelIdxResp)
  val wreqInvalidAddr = io.in.aw.valid && !woutSelVec.asUInt.orR

  (io.out zip woutSelVec).map {
    case (o, v) => {
      o.aw.bits := io.in.aw.bits
      o.aw.valid := v && (io.in.aw.valid && (w_state === s_idle))
      o.w.bits := io.in.w.bits
      o.w.valid := v && io.in.w.valid
      o.b.ready := v
    }
  }
  for (i <- 0 until addressSpace.length) {
    when(woutSelIdx === i.U) {
      io.out(i).aw.bits.addr := io.in.aw.bits.addr - addressSpace(i)._1.U
    }
  }

  switch(w_state) {
    is(s_idle) {
      when(woutSel.aw.fire()) { w_state := s_resp }
      when(wreqInvalidAddr) { w_state := s_error }
    }
    is(s_resp) { when(woutSelResp.b.fire()) { w_state := s_idle } }
    is(s_error) { when(io.in.b.fire()) { w_state := s_idle } }
  }

  io.in.b.valid := woutSelResp.b.valid || w_state === s_error
  io.in.b.bits <> woutSelResp.b.bits
  woutSelResp.b.ready := io.in.b.ready
  io.in.aw.ready := (woutSel.aw.ready && w_state === s_idle) || wreqInvalidAddr
  io.in.w.ready := woutSel.w.ready
}

class AXI4XBarNto1(n: Int, val idBits: Int = AXI4Parameters.idBits) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Vec(n, new AXI4))
    val out = new AXI4
  })

  val s_idle :: s_readResp :: s_writeResp :: Nil = Enum(3)
  val r_state = RegInit(s_idle)
  val inputArb_r = Module(new Arbiter(new AXI4BundleA(idBits), n))
  (inputArb_r.io.in zip io.in.map(_.ar)).map { case (arb, in) => arb <> in }
  val thisReq_r = inputArb_r.io.out
  val inflightSrc_r = Reg(UInt(log2Ceil(n).W))

  io.out.ar.bits := Mux(
    r_state === s_idle,
    thisReq_r.bits,
    io.in(inflightSrc_r).ar.bits
  )

  io.out.ar.valid := thisReq_r.valid && (r_state === s_idle)
  io.in.map(_.ar.ready := false.B)
  thisReq_r.ready := io.out.ar.ready && (r_state === s_idle)

  io.in.map(_.r.bits := io.out.r.bits)
  io.in.map(_.r.valid := false.B)
  (io.in(inflightSrc_r).r, io.out.r) match {
    case (l, r) => {
      l.valid := r.valid
      r.ready := l.ready
    }
  }

  switch(r_state) {
    is(s_idle) {
      when(thisReq_r.fire()) {
        inflightSrc_r := inputArb_r.io.chosen
        io.in(inputArb_r.io.chosen).ar.ready := true.B
        when(thisReq_r.valid) { r_state := s_readResp }
      }
    }
    is(s_readResp) {
      when(io.out.r.fire() && io.out.r.bits.last) { r_state := s_idle }
    }
  }

  val w_state = RegInit(s_idle)
  val inputArb_w = Module(new Arbiter(new AXI4BundleA(idBits), n))
  (inputArb_w.io.in zip io.in.map(_.aw)).map { case (arb, in) => arb <> in }
  val thisReq_w = inputArb_w.io.out
  val inflightSrc_w = Reg(UInt(log2Ceil(n).W))

  io.out.aw.bits := Mux(
    w_state === s_idle,
    thisReq_w.bits,
    io.in(inflightSrc_w).aw.bits
  )

  io.out.aw.valid := thisReq_w.valid && (w_state === s_idle)
  io.in.map(_.aw.ready := false.B)
  thisReq_w.ready := io.out.aw.ready && (w_state === s_idle)

  io.out.w.valid := io.in(inflightSrc_w).w.valid
  io.out.w.bits := io.in(inflightSrc_w).w.bits
  io.in.map(_.w.ready := false.B)
  io.in(inflightSrc_w).w.ready := io.out.w.ready

  io.in.map(_.b.bits := io.out.b.bits)
  io.in.map(_.b.valid := false.B)
  (io.in(inflightSrc_w).b, io.out.b) match {
    case (l, r) => {
      l.valid := r.valid
      r.ready := l.ready
    }
  }

  switch(w_state) {
    is(s_idle) {
      when(thisReq_w.fire()) {
        inflightSrc_w := inputArb_w.io.chosen
        io.in(inputArb_w.io.chosen).aw.ready := true.B
        when(thisReq_w.valid) { w_state := s_writeResp }
      }
    }
    is(s_writeResp) {
      when(io.out.b.fire()) { w_state := s_idle }
    }
  }
}

