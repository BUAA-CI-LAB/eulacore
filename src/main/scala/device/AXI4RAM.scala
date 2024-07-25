package device

import chisel3._
import chisel3.util._
import chisel3.util.experimental.loadMemoryFromFile
import eulacore.HasEulaCoreParameter
import bus.axi4._
import top.Settings
import utils._

class RAMHelper(memByte: Int) extends BlackBox with HasEulaCoreParameter {
  val io = IO(new Bundle {
    val clk = Input(Clock())
    val rIdx = Input(UInt(DataBits.W))
    val rdata = Output(UInt(DataBits.W))
    val wIdx = Input(UInt(DataBits.W))
    val wdata = Input(UInt(DataBits.W))
    val wmask = Input(UInt(DataBits.W))
    val wen = Input(Bool())
    val en = Input(Bool())
  }).suggestName("io")
}

class AXI4RAM[T <: AXI4Lite](_type: T = new AXI4, memByte: Int, // TODO : dose need set memByte BigInt ?
  useBlackBox: Boolean = false) extends AXI4SlaveModule(_type) with HasEulaCoreParameter {

  val offsetBits = log2Up(memByte)
  val offsetMask = (1 << offsetBits) - 1
//  def index(addr: UInt) = (addr & offsetMask.U) >> log2Ceil(DataBytes)
  // NOTE : be careful that we use addr - resetvector to access memory, so other section should always after text section
  def index(addr: UInt) = (addr - Settings.getLong("RAMBase").U) >> log2Ceil(DataBytes)
  def inRange(idx: UInt) = idx < (memByte / 8).U

  val wIdx = index(waddr) + writeBeatCnt
  val rIdx = index(raddr) + readBeatCnt
  val wen = in.w.fire() // && inRange(wIdx)

  val rdata = if (useBlackBox) {
    val mem = Module(new RAMHelper(memByte))
    mem.io.clk := clock
    mem.io.rIdx := rIdx
    mem.io.wIdx := wIdx
    mem.io.wdata := in.w.bits.data
    mem.io.wmask := fullMask
    mem.io.wen := wen
    mem.io.en := ren
    mem.io.rdata
  } else {
    val mem = Mem(memByte / DataBytes, Vec(DataBytes, UInt(8.W)))

    val wdata = VecInit.tabulate(DataBytes) { i => in.w.bits.data(8*(i+1)-1, 8*i) }
    when (wen) { mem.write(wIdx, wdata, in.w.bits.strb.asBools) }

    Cat(mem.read(rIdx).reverse)
  }

  in.r.bits.data := RegEnable(next = rdata, enable = ren, init = 0.U.asTypeOf(rdata))
}
