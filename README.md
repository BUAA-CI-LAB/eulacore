# EuLA Core 简介

EuLA Core 是由 Chisel 实现的顺序单发射九级流水线处理器，其完全基于 EuLA-Env 敏捷开发并将作为本项目的接入样例。

EuLA Core 进行 Verilator 仿真功能验证的原理是：

* 处理器内部实例化 Difftest* 模块并连线；

* 仿真顶层实例化处理器；

* 仿真顶层和 difftest 共同编译为 C++ 行为模型 emu，emu 可运行由 AM 编译生成的裸机程序并进行差分测试。

在 EuLA Core 中，处理器和仿真顶层（亦可理解为仿真 SoC）均通过 Chisel 实现，而为了支持使用其他语言编写处理器，本项目将编译过程分为两阶段，以此是编译仿真 SoC，生成 SimTop.v 文件。SimTop.v 文件中包含 RAM/UART/Confreg 等模块定义、实例化与相应连接，而处理器以黑盒形式出现，即 SimTop.v 中只存在处理器的实例化，但没有对应实现，如下：

```verilog 
mycpu_mega_top core ( // @[EulaSoc.scala 27:20]
    .awready(core_awready),
    .awvalid(core_awvalid),
    .awaddr(core_awaddr),
    .awprot(core_awprot),
    .awid(core_awid),
    .awuser(core_awuser),
    .awlen(core_awlen),
    .awsize(core_awsize),
    .awburst(core_awburst),
    .awlock(core_awlock),
    .awcache(core_awcache),
    .awqos(core_awqos),
    .wready(core_wready),
    .wvalid(core_wvalid),
    .wdata(core_wdata),
    .wstrb(core_wstrb),
    .wlast(core_wlast),
    .bready(core_bready),
    .bvalid(core_bvalid),
    .bresp(core_bresp),
    .bid(core_bid),
    .buser(core_buser),
    .arready(core_arready),
    .arvalid(core_arvalid),
    .araddr(core_araddr),
    .arprot(core_arprot),
    .arid(core_arid),
    .aruser(core_aruser),
    .arlen(core_arlen),
    .arsize(core_arsize),
    .arburst(core_arburst),
    .arlock(core_arlock),
    .arcache(core_arcache),
    .arqos(core_arqos),
    .rready(core_rready),
    .rvalid(core_rvalid),
    .rresp(core_rresp),
    .rdata(core_rdata),
    .rlast(core_rlast),
    .rid(core_rid),
    .ruser(core_ruser),
    .ext_int(core_ext_int),
    .clock(core_clock),
    .reset(core_reset),
    .global_reset(core_global_reset),
    .debug_wb_pc(core_debug_wb_pc),
    .debug_wb_rf_wen(core_debug_wb_rf_wen),
    .debug_wb_rf_wnum(core_debug_wb_rf_wnum),
    .debug_wb_rf_wdata(core_debug_wb_rf_wdata),
    .debug_wb_instr(core_debug_wb_instr),
    .aclk(core_aclk),           // fake signal, nothing to do when sim
    .aresetn(core_aresetn)      // fake signal, nothing to do when sim
  );
```

可见，为了使得 SimTop 模块能够编译为 emu，我们需要自行添加处理器顶层模块 mycpu_mega_top 的定义，例如，如果处理器通过多个 verilog 文件编写而成，处理器顶层模块 mycpu_mega_top（在 chiplab 中称为 core_top）位于 eulacore/core.v 文件当中，则我们需要在 SimTop.v 文件中引入 core.v 文件，如下:

```verilog
// SimTop.v
`include "../core.v" // SimTop.v is in eulacore/build
module AXI4RAM(
  input         clock,
  input         reset,
  output        io_in_aw_ready,
  input         io_in_aw_valid,
  ...
  output        io_in_r_bits_user
);

...
```

至此，我们得到了完整的仿真 SoC 文件，并附加了对应的处理器实现，已经可以进行 emu 的编译。

## How It Work

### 编译仿真顶层 EuLA-SoC

```bash
$ make verilog # 默认 SOC=eulasoc PROD=top
```

### 添加处理器实现文件

这里以本项目提供的 EuLA Core 为例，我们需要编译 EuLA Core，通过如下命令：

```bash
$ make verilog PROD=cpu # 默认 SOC=eulasoc
```

编译完成后，得到 eulacore/build/TopMain.v 文件

> Chisel 通过多源文件编译最终会生成一个 .v 文件，但我们将编译过程分为两阶段，因此至少会有两个 .v 文件。仿真 SoC 和样例处理器 EuLA Core 中都使用到了 Chisel 相同的 Arbiter 模块，由于 Chisel 编译时会将没有用到的接口优化掉，因此 Arbiter 在两个 .v 文件中重名并且接口定义不同，这样如果简单地将样例处理器 include 进来会出现重复定义的错误。如果你的处理器未使用 Chisel 开发，或者使用 Chisel 开发的处理器未使用 Arbite，则不会遇到这个问题。作为演示，我们采取一种简单的调整方式，手动将 SimTop.v 中的 Arbiter 模块更名，例如更为 Arbiter_soc，具体优化有三处：

```verilog
// SimTop.v
...
module Arbiter_soc( // 将 Arbiter 更改为 Arbiter_soc
  input         io_in_0_valid,
  input  [31:0] io_in_0_bits_addr,
  input         io_in_0_bits_id,
  input         io_in_0_bits_user,
  input  [7:0]  io_in_0_bits_len,
  input  [2:0]  io_in_0_bits_size,
  input  [1:0]  io_in_0_bits_burst,
  input         io_in_1_valid,
  input  [31:0] io_in_1_bits_addr,
  input         io_in_1_bits_id,
  input         io_in_1_bits_user,
  input  [7:0]  io_in_1_bits_len,
  input  [2:0]  io_in_1_bits_size,
  input  [1:0]  io_in_1_bits_burst,
  input         io_out_ready,
  output        io_out_valid,
  output [31:0] io_out_bits_addr,
  output        io_out_bits_id,
  output        io_out_bits_user,
  output [7:0]  io_out_bits_len,
  output [2:0]  io_out_bits_size,
  output [1:0]  io_out_bits_burst,
  output        io_chosen
);
...
Arbiter_soc inputArb_r ( // 将 Arbiter 更改为 Arbiter_soc
    .io_in_0_valid(inputArb_r_io_in_0_valid),
    .io_in_0_bits_addr(inputArb_r_io_in_0_bits_addr),
    .io_in_0_bits_id(inputArb_r_io_in_0_bits_id),
    .io_in_0_bits_user(inputArb_r_io_in_0_bits_user),
    .io_in_0_bits_len(inputArb_r_io_in_0_bits_len),
    .io_in_0_bits_size(inputArb_r_io_in_0_bits_size),
    .io_in_0_bits_burst(inputArb_r_io_in_0_bits_burst),
    .io_in_1_valid(inputArb_r_io_in_1_valid),
    .io_in_1_bits_addr(inputArb_r_io_in_1_bits_addr),
    .io_in_1_bits_id(inputArb_r_io_in_1_bits_id),
    .io_in_1_bits_user(inputArb_r_io_in_1_bits_user),
    .io_in_1_bits_len(inputArb_r_io_in_1_bits_len),
    .io_in_1_bits_size(inputArb_r_io_in_1_bits_size),
    .io_in_1_bits_burst(inputArb_r_io_in_1_bits_burst),
    .io_out_ready(inputArb_r_io_out_ready),
    .io_out_valid(inputArb_r_io_out_valid),
    .io_out_bits_addr(inputArb_r_io_out_bits_addr),
    .io_out_bits_id(inputArb_r_io_out_bits_id),
    .io_out_bits_user(inputArb_r_io_out_bits_user),
    .io_out_bits_len(inputArb_r_io_out_bits_len),
    .io_out_bits_size(inputArb_r_io_out_bits_size),
    .io_out_bits_burst(inputArb_r_io_out_bits_burst),
    .io_chosen(inputArb_r_io_chosen)
  );
  Arbiter_soc inputArb_w ( // 将 Arbiter 更改为 Arbiter_soc
    .io_in_0_valid(inputArb_w_io_in_0_valid),
    .io_in_0_bits_addr(inputArb_w_io_in_0_bits_addr),
    .io_in_0_bits_id(inputArb_w_io_in_0_bits_id),
    .io_in_0_bits_user(inputArb_w_io_in_0_bits_user),
    .io_in_0_bits_len(inputArb_w_io_in_0_bits_len),
    .io_in_0_bits_size(inputArb_w_io_in_0_bits_size),
    .io_in_0_bits_burst(inputArb_w_io_in_0_bits_burst),
    .io_in_1_valid(inputArb_w_io_in_1_valid),
    .io_in_1_bits_addr(inputArb_w_io_in_1_bits_addr),
    .io_in_1_bits_id(inputArb_w_io_in_1_bits_id),
    .io_in_1_bits_user(inputArb_w_io_in_1_bits_user),
    .io_in_1_bits_len(inputArb_w_io_in_1_bits_len),
    .io_in_1_bits_size(inputArb_w_io_in_1_bits_size),
    .io_in_1_bits_burst(inputArb_w_io_in_1_bits_burst),
    .io_out_ready(inputArb_w_io_out_ready),
    .io_out_valid(inputArb_w_io_out_valid),
    .io_out_bits_addr(inputArb_w_io_out_bits_addr),
    .io_out_bits_id(inputArb_w_io_out_bits_id),
    .io_out_bits_user(inputArb_w_io_out_bits_user),
    .io_out_bits_len(inputArb_w_io_out_bits_len),
    .io_out_bits_size(inputArb_w_io_out_bits_size),
    .io_out_bits_burst(inputArb_w_io_out_bits_burst),
    .io_chosen(inputArb_w_io_chosen)
  );
```

完成上述操作后，在 SimTop.v 文件中引入处理器实现文件（位于 eulacore/build/TopMain.v）

```verilog
`include "TopMain.v"
module AXI4RAM(
  input         clock,
  input         reset,
  output        io_in_aw_ready,
  input         io_in_aw_valid,
  ...
  output        io_in_r_bits_user
);
```

### 编译 emu

```bash
$ make emu EMU_TRACE=1 # SOC=eulasoc PROD=top

# EMU_TRACE=1 参数用于导出波形
```

生成产物位于 eulacore/build/emu

### 运行仿真测试

```bash
$ cd ${PROJECT_ROOT}/eulacore/build
$ ./emu -i ${AM_HOME}/apps/coremark/build/coremark-la32r-eula.bin

# 如需导出波形，可添加 --dump-wave --wave-path="<your wave path>" 参数，注意前提是在编译 emu 的时候已经添加了 EMU_TRACE=1 参数
# 如需控制导出波形的周期，可添加 -b <begin cycle> -e <env cycle> 参数
```

### 查看波形

```bash
gtkwave "<your wave path>"
```