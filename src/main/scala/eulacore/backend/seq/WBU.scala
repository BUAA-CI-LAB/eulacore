package eulacore

import chisel3._
import chisel3.util._
import chisel3.util.experimental.BoringUtils
import utils._
import difftest._

class WBU(implicit val p: EulaCoreConfig) extends EulaCoreModule{
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new CommitIO))
    val wb = new WriteBackIO
    val redirect = new RedirectIO
  })

  io.wb.rfWen := io.in.bits.decode.ctrl.rfWen && io.in.valid
  io.wb.rfDest := io.in.bits.decode.ctrl.rfDest
  io.wb.rfData := io.in.bits.commits(io.in.bits.decode.ctrl.fuType)

  io.in.ready := true.B

  io.redirect := io.in.bits.decode.cf.redirect
  io.redirect.valid := io.in.bits.decode.cf.redirect.valid && io.in.valid


  val falseWire = WireInit(false.B) // make BoringUtils.addSource happy
  BoringUtils.addSource(io.in.valid, "perfCntCondMinstret")
  BoringUtils.addSource(falseWire, "perfCntCondMultiCommit")

  LADebug(io.in.valid, "[COMMIT] pc = 0x%x inst %x wen %x wdst %x wdata %x mmio %x intrNO %x\n",
    io.in.bits.decode.cf.pc, io.in.bits.decode.cf.instr, io.wb.rfWen, io.wb.rfDest, io.wb.rfData, io.in.bits.isMMIO, io.in.bits.intrNO)
  
  if (!p.FPGAPlatform) {
    val difftest_commit = Module(new DifftestInstrCommit)
    difftest_commit.io.clock    := clock
    difftest_commit.io.coreid   := 0.U
    difftest_commit.io.index    := 0.U

    difftest_commit.io.valid    := RegNext(io.in.valid)
    difftest_commit.io.pc       := RegNext(SignExt(io.in.bits.decode.cf.pc, AddrBits))
    difftest_commit.io.instr    := RegNext(io.in.bits.decode.cf.instr)
    difftest_commit.io.skip     := RegNext(io.in.bits.isMMIO)
    difftest_commit.io.tlbModify := RegNext(io.in.bits.tlbModifyInst)
    difftest_commit.io.difftestExceptionSkip := RegNext(io.in.bits.difftestExceptionSkip)
    difftest_commit.io.isRVC    := RegNext(io.in.bits.decode.cf.instr(1,0)=/="b11".U)
    difftest_commit.io.rfwen    := RegNext(io.wb.rfWen && io.wb.rfDest =/= 0.U) // && valid(ringBufferTail)(i) && commited(ringBufferTail)(i)
    difftest_commit.io.fpwen    := false.B
    // difftest.io.wdata    := RegNext(io.wb.rfData)
    difftest_commit.io.wdest    := RegNext(io.wb.rfDest)
    difftest_commit.io.wpdest   := RegNext(io.wb.rfDest)

    val difftest_wb = Module(new DifftestIntWriteback)
    difftest_wb.io.clock := clock
    difftest_wb.io.coreid := 0.U
    difftest_wb.io.valid := RegNext(io.wb.rfWen && io.wb.rfDest =/= 0.U)
    difftest_wb.io.dest := RegNext(io.wb.rfDest)
    difftest_wb.io.data := RegNext(io.wb.rfData)

    val diffStore = Module(new DifftestStoreEvent)
    diffStore.io.clock := clock
    diffStore.io.coreid := 0.U
    diffStore.io.index := 0.U
    diffStore.io.valid := RegNext(io.in.valid && io.in.bits.storeCheck.valid)
    diffStore.io.storeAddr := RegNext(io.in.bits.storeCheck.storeAddr)
    diffStore.io.storeData := RegNext(io.in.bits.storeCheck.storeData)
    diffStore.io.pc := RegNext(io.in.bits.decode.cf.pc)
  } else {
    BoringUtils.addSource(RegNext(io.in.bits.decode.cf.pc), "DEBUG_WB_PC")
    BoringUtils.addSource(RegNext(Fill(4, io.wb.rfWen)), "DEBUG_WB_RF_WEN")
    BoringUtils.addSource(RegNext(io.wb.rfDest), "DEBUG_WB_RF_WNUM")
    BoringUtils.addSource(RegNext(io.wb.rfData), "DEBUG_WB_RF_WDATA")
    BoringUtils.addSource(RegNext(io.in.bits.decode.cf.instr), "DEBUG_WB_INSTR")
  }
}
