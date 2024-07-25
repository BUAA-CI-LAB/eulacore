

package device

import chisel3._
import chisel3.util._

import bus.axi4._
import utils._
import difftest._

// only simulate uart8250 write behavior
// reference : https://github.com/riscv-software-src/opensbi/blob/master/lib/utils/serial/uart8250.c
class AXI4UART8250 extends AXI4SlaveModule(new AXI4, _extra = new UARTIO)
{
  val reg0 = RegInit(0.U(32.W))
  val reg1 = RegInit(0x2000.U(32.W))
  val reg2 = RegInit(0.U(32.W))
  val reg3 = RegInit(0.U(32.W))
  
  val raddrLatch = HoldUnless(raddr, in.ar.fire)

  io.extra.get.out.valid := (waddr(3,0) === 0.U && in.w.fire())
  io.extra.get.out.ch := in.w.bits.data(7,0)
  io.extra.get.in.valid := false.B

  val mapping = Map(
    RegMap(0x0, reg0),
    RegMap(0x4, reg1),
    RegMap(0x8, reg2),
    RegMap(0xc, reg3),

  )

  RegMap.generate(mapping, Cat(raddrLatch(3,2), 0.U(2.W)), in.r.bits.data,
    Cat(waddr(3,2), 0.U(2.W)), in.w.fire(), in.w.bits.data, MaskExpand(in.w.bits.strb)
  )

//  when (in.r.fire) {
//    printf("uart read : addr=0x%x,data=0x%x\n", raddrLatch, in.r.bits.data)
//  }
//  when (in.w.fire) {
//    printf("uart write : addr=0x%x, wdata=0x%x, wmask=0x%x\n", in.aw.bits.addr, in.w.bits.data, in.w.bits.strb)
//  }

  when (!((reg1 & 0x2000.U).orR())) {
    reg1 := reg1 | 0x2000.U
  }
  when ((reg1 & 1.U).orR) {
    reg1 := Cat(reg1(31, 1), 0.U(1.W))
  }
//  assert((reg1 & 0x2000.U).orR() & !(reg1 & 1.U), "uart only support write, not support read")
  assert(!(in.w.fire ^ in.aw.fire), "axi4 w channel and aw channel need fire at same time")
}
