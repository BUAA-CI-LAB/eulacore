

package utils

import chisel3._
import chisel3.util._

object HoldUnless {
  def apply[T <: Data](x: T, en: Bool): T = Mux(en, x, RegEnable(x, 0.U.asTypeOf(x), en))
}

object ReadAndHold {
  def apply[T <: Data](x: Mem[T], addr: UInt, en: Bool): T = HoldUnless(x.read(addr), en)
  def apply[T <: Data](x: SyncReadMem[T], addr: UInt, en: Bool): T = HoldUnless(x.read(addr, en), RegNext(en))
}
object HoldReleaseLatch {
  def apply(valid: Bool, release: Bool, flush: Bool): Bool = {
    val bit = RegInit(false.B)
    when(flush) {bit := false.B}
      .elsewhen(valid && !release) {bit := true.B}
      .elsewhen(release) {bit := false.B}
    bit || valid
  }
}
