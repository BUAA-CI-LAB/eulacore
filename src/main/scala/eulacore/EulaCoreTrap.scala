package eulacore

import chisel3._
import chisel3.util._

object EulaCoreTrap {
  def TRAP = BitPat("b0 0 0 0 0 0 0 0 0 0 1 0 1 0 1 1 0_000000000010001") // syscall 0x11

}
