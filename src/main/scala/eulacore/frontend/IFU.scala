package eulacore

import chisel3._
import chisel3.util._
import chisel3.util.experimental.BoringUtils
import utils._
import bus.simplebus._
import top.Settings
import difftest._
import eulacore.mem.HasMemAccessMaster

trait HasResetVector {
  val resetVector = Settings.getLong("ResetVector")
}

class IFU_embedded extends EulaCoreModule with HasResetVector with HasMemAccessMaster {

  val io = IO(new Bundle {
    val imem = new SimpleBusUC(userBits = immuUserBits, addrBits = VAddrBits) // userBits need contain brIdx
    val out = Decoupled(new CtrlFlowIO)
    val redirect = Flipped(new RedirectIO)
    val flushVec = Output(UInt(4.W))
    val bpFlush = Output(Bool())
  })

  // pc
  val pc = RegInit(resetVector.U(32.W))
  val pcUpdate = io.redirect.valid || io.imem.req.fire()
  val snpc = pc + 4.U  // sequential next pc

  val bpu = Module(new BPU_embedded)

  // predicted next pc
  val pnpc = bpu.io.out.target
  val npc = Mux(io.redirect.valid, io.redirect.target, Mux(bpu.io.out.valid, pnpc, snpc))
  
  bpu.io.in.pc.valid := io.imem.req.fire() // only predict when Icache accepts a request
  bpu.io.in.pc.bits := npc  // predict one cycle early
  bpu.io.flush := io.redirect.valid

  when (pcUpdate) { pc := npc }

  io.flushVec := Mux(io.redirect.valid, "b1111".U, 0.U)
  io.bpFlush := false.B

  val reqUserBits = Wire(new ImmuUserBundle)
  reqUserBits.pc := pc
  reqUserBits.npc := npc
  reqUserBits.brIdx := Mux(io.redirect.valid, 0.U, Cat(0.U(3.W), bpu.io.out.valid))
  reqUserBits.memAccessMaster := FETCH
  reqUserBits.tlbExcp := 0.U.asTypeOf(reqUserBits.tlbExcp)
  reqUserBits.mat := 0.U

  io.imem := DontCare
  io.imem.req.bits.apply(addr = pc, size = "b10".U, cmd = SimpleBusCmd.read, wdata = 0.U, wmask = 0.U, user = reqUserBits.asUInt())
  io.imem.req.valid := io.out.ready
  io.imem.resp.ready := io.out.ready || io.flushVec(0)

  io.out.bits := DontCare
  // send nop when check excp
  io.out.bits.instr := Mux(io.out.bits.exceptionVec.asUInt().orR, La32rInstructions.NOP, io.imem.resp.bits.rdata)
  val respUserBits = io.imem.resp.bits.user.get.asTypeOf(new ImmuUserBundle)
  io.out.bits.brIdx := respUserBits.brIdx
  io.out.bits.pc := respUserBits.pc
  io.out.bits.pnpc := respUserBits.npc

  io.out.valid := io.imem.resp.valid && !io.flushVec(0)
  io.out.bits.exceptionVec.foreach(_ := false.B)

    io.out.bits.exceptionVec(ADEF) := io.out.valid && io.out.bits.pc(1, 0).orR
    io.out.bits.exceptionVec(TLBR) := io.out.valid && respUserBits.tlbExcp.tlbRefillExcp
    io.out.bits.exceptionVec(PIF) := io.out.valid && respUserBits.tlbExcp.fetchPageInvalidExcp
    io.out.bits.exceptionVec(PPI) := io.out.valid && respUserBits.tlbExcp.pagePrivInvalidExcp



  BoringUtils.addSource(BoolStopWatch(io.imem.req.valid, io.imem.resp.fire()), "perfCntCondMimemStall")
  BoringUtils.addSource(io.flushVec.orR, "perfCntCondMifuFlush")
}
