TOP = TopMain
BUILD_DIR = ./build
TOP_V = $(BUILD_DIR)/$(TOP).v
SCALA_FILE = $(shell find ./src/main/scala -name '*.scala')
TEST_FILE = $(shell find ./src/test/scala -name '*.scala')

USE_READY_TO_RUN_NEMU = true

SIMTOP = top.TopMain
IMAGE ?= ready-to-run/linux.bin

DATAWIDTH ?= 32
SOC ?= eulasoc # megasoc perftest
CORE  ?= inorder
PROD ?= top

.DEFAULT_GOAL = verilog

ASIC ?= 
MEM_GEN = ./scripts/vlsi_mem_gen
SRAM_MODEL = sram_model.v
MEM_GEN_COMMAND = $(MEM_GEN) $(@D)/build/$(@F).conf >> $(@D)/$(SRAM_MODEL)

ifeq ($(PROD),cpu)
TAR_TOP = TopMain
PACKAGE = top
else
TAR_TOP = SimTop
PACKAGE = sim
endif

TAR_TOP_V = $(BUILD_DIR)/$(TAR_TOP).v

$(TAR_TOP_V): $(SCALA_FILE)
	mkdir -p $(@D)
	echo $(MEM_GEN_COMMAND)
	./mill chiselModule.runMain top.TopMain -td $(@D) --output-file $(@F) SOC=$(SOC) CORE=$(CORE) PROD=$(PROD) --infer-rw --repl-seq-mem -c:$(TOP):-o:$(@D)/$(@F).conf
	rm -f $(@D)/$(SRAM_MODEL)
	$(MEM_GEN_COMMAND)
	cat $(@D)/$(SRAM_MODEL) >> $(TAR_TOP_V)

verilog: $(TAR_TOP_V)

emu: verilog
	$(MAKE) -C ./difftest emu

emu-run: sim-verilog
	$(MAKE) -C ./difftest emu-run

init:
	git submodule update --init

clean:
	rm -rf $(BUILD_DIR)

bsp:
	./mill -i mill.bsp.BSP/install
idea:
	./mill -i mill.scalalib.GenIdea/idea

lint:
	verilator --lint-only -Wall $(TOP_V) -Wno-DECLFILENAME

.PHONY: idea verilog emu clean help $(REF_SO)
