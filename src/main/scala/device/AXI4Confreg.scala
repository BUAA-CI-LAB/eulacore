package device

import chisel3._
import chisel3.util._

import bus.axi4._
import utils._
import difftest._


trait HasConfregConst {
  val CRO_ADDR        = 0x8000
  val CR1_ADDR        = 0x8010
  val CR2_ADDR        = 0x8020
  val CR3_ADDR        = 0x8030
  val CR4_ADDR        = 0x8040
  val CR5_ADDR        = 0x8050
  val CR6_ADDR        = 0x8060
  val CR7_ADDR        = 0x8070
  val LED_ADDR        = 0xf020
  val LED_RG0_ADDR    = 0xf030
  val LED_RG1_ADDR    = 0xf040
  val NUM_ADDR        = 0xf050
  val SWITCH_ADDR     = 0xf060
  val BTN_KEY_ADDR    = 0xf070
  val BTN_STEP_ADDR   = 0xf080
  val SW_INTER_ADDR   = 0xf090
  val TIMER_ADDR      = 0xe000
  val FREQ_ADDR       = 0xf030  // TODO : FREQ ADDR IS bfd0_f030
  val IO_SIMU_ADDR    = 0xff00
  val VIRTUAL_UART_ADDR = 0xff10
  val SIMU_FLAG_ADDR  = 0xff20
  val OPEN_TRACE_ADDR = 0xff30
  val NUM_MONITOR_ADDR = 0xff40
}

// note : does not implement {simulation flag},{led},{switch},{btn key},{btn step},{leg rg},{digital number},{FREQ}
// timer is in the same clock domain, different with chiplab IP


class AXI4Confreg extends AXI4SlaveModule(new AXI4) with HasConfregConst
{
  val cr = List.fill(8)(RegInit(0.U(32.W)))
  val timer = RegInit(0.U(32.W))
  val io_simu = RegInit(0.U(32.W))
  val open_trace = RegInit(1.U(32.W))
  val num_monitor = RegInit(1.U(32.W))
  val virtual_uart_data = RegInit(0.U(32.W))

  // timer
  timer := timer + 1.U

  val raddrLatch = HoldUnless(raddr, in.ar.fire)

  val mapping = Map(
    RegMap(CRO_ADDR, cr(0)),
    RegMap(CR1_ADDR, cr(1)),
    RegMap(CR2_ADDR, cr(2)),
    RegMap(CR3_ADDR, cr(3)),
    RegMap(CR4_ADDR, cr(4)),
    RegMap(CR5_ADDR, cr(5)),
    RegMap(CR6_ADDR, cr(6)),
    RegMap(CR7_ADDR, cr(7)),
    RegMap(TIMER_ADDR, timer),
    RegMap(IO_SIMU_ADDR, io_simu),
    RegMap(OPEN_TRACE_ADDR, open_trace),
    RegMap(NUM_MONITOR_ADDR, num_monitor)
  )

  val wdata = LookupTreeDefault(waddr(15, 0), in.w.bits.data, List(
    IO_SIMU_ADDR.U -> Cat(in.w.bits.data(15, 0), in.w.bits.data(31, 16)),
    OPEN_TRACE_ADDR.U -> Cat(0.U(31.W), in.w.bits.data.orR),
    NUM_MONITOR_ADDR.U -> Cat(0.U(31.W), in.w.bits.data(0)),
    VIRTUAL_UART_ADDR.U -> Cat(0.U(24.W), in.w.bits.data(7, 0)),
  ))

  RegMap.generate(mapping, raddrLatch(15, 0), in.r.bits.data,
    waddr(15, 0), in.w.fire, wdata, Fill(32, in.w.bits.strb.orR))

}
