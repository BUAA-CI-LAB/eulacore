import os
import sys
import logging
import subprocess
import re
import random

seed = random.randint(1, 99999)
print("use seed="+str(seed))

NOOP_HOME = os.getenv("NOOP_HOME")
NEMU_HOME = os.getenv("NEMU_HOME")
AM_HOME = os.getenv("AM_HOME")
ISA = "la32r"
PLATFORM = "eula"

if NOOP_HOME == None or NEMU_HOME == None or AM_HOME == None:
    sys.stderr.write("Environment variables are not set correctly\n")
    raise SystemExit(1)

emu_path = os.path.join(NOOP_HOME, "build/emu")
nemu_path = os.path.join(NEMU_HOME, "build/", ISA + "-nemu-interpreter-so")
print(emu_path)

if not os.path.exists(emu_path) or not os.path.exists(nemu_path):
    sys.stderr.write("nemu or emu are not set correctly\n")
    raise SystemExit(1)

cputest_dir = os.path.join(AM_HOME, "tests/cputest/build")
# command : ls | awk '{print substr($0, 1, length($0) - 2)}' | awk '{print "\""$0"\""}' | awk '{print $0 ","}'
cputest_list = ["add-longlong",
                "add",
                "bit",
                "bubble-sort",
                "div",
                "dummy",
                "fact",
                "fib",
                "goldbach",
                "hello-str",
                "if-else",
                "leap-year",
                "load-store",
                "matrix-mul",
                "max",
                "min3",
                "mov-c",
                "movsx",
                "mul-longlong",
                "pascal",
                "prime",
                "quick-sort",
                "recursion",
                "select-sort",
                "shift",
                "shuixianhua",
                "string",
                "sub-longlong",
                "sum",
                "switch",
                "to-lower-case",
                "unalign",
                "wanshu",
]

misctest_dir = os.path.join(AM_HOME, "tests/misctest/build")
misctest_list = ["csr-read-write", "tlb-read-write", "preld-test", "confreg-test"]

cachetest_dir = os.path.join(AM_HOME, "tests/cachetest/build")
cachetest_list = ["access", "cache-flush", "dbar-test", "dcache-walk", "ibar-test", "load-after-store-test"]

nscscc_func_test_dir = os.path.join(AM_HOME, "tests/chiplabtest/build")
nscscc_func_test_list = ["chiplabfunc.bin"]

nscscc_perf_dir = os.path.join(AM_HOME, "apps/nscscc_perf/obj")
nscscc_perf_list = ["bitcount",
                    "bubble_sort",
                    "coremark",
                    "crc32",
                    "dhrystone",
                    "quick_sort",
                    "select_sort",
                    "sha",
                    "stream_copy",
                    "stringsearch",]

apps_dir = os.path.join(AM_HOME, "apps")
apps_list = [
    "coremark",
    "dhrystone",
    "microbench",
]



runlog = open("./run.log",'wt+')
logpath = os.path.join(os.getcwd(), "run.log")

logger = logging.getLogger()
logger.setLevel(logging.DEBUG)
formatter = logging.Formatter(
    '%(asctime)s - %(name)s - %(levelname)s: - %(message)s',
    datefmt='%Y-%m-%d %H:%M:%S')
# 使用FileHandler输出到文件
fh = logging.FileHandler('run.log')
fh.setLevel(logging.DEBUG)
fh.setFormatter(formatter)

# 使用StreamHandler输出到屏幕
ch = logging.StreamHandler()
ch.setLevel(logging.DEBUG)
ch.setFormatter(formatter)

# 添加两个Handler
logger.addHandler(ch)
logger.addHandler(fh)

print("begin batch run test, output to " + logpath)

def run_single_test(tp_path):
    if not os.path.exists(tp_path):
        logging.debug(tp_path + " does not exists, skip")
        return
    runcommand = (emu_path, f"--seed={seed}", "-i", tp_path, "-b", "0", "-e", "0", "-l", "0")
    logging.debug("runcommand:" + " ".join(runcommand))
    try:
        out_bytes = subprocess.check_output(runcommand, stderr=subprocess.STDOUT)
    except subprocess.CalledProcessError as e:
        out_bytes = e.output
        print(out_bytes.decode('utf-8'), file=runlog)
        exit(1)
    print(out_bytes.decode('utf-8'), file=runlog)
    return out_bytes.decode('utf-8')

# cputest
cputestnum = len(cputest_list)
passcputestnum = 0
logging.debug("begin cputest")

for tp in cputest_list:
    tp_path = os.path.join(cputest_dir, tp + "-" + ISA + "-" + PLATFORM + ".bin")
    run_single_test(tp_path)
logging.debug("all cputest pass")

# misctest
misctestnum = len(misctest_list)
logging.debug("begin misctest")

for tp in misctest_list:
    tp_path = os.path.join(misctest_dir, tp + "-" + ISA + "-" + PLATFORM + ".bin")
    run_single_test(tp_path)
logging.debug("all misctest pass")

# cachetest
logging.debug("begin cachetest")

for tp in cachetest_list:
    tp_path = os.path.join(cachetest_dir, tp + "-" + ISA + "-" + PLATFORM + ".bin")
    run_single_test(tp_path)
logging.debug("all cachetest pass")

# nscscc func test
nscscc_func_test_num = len(nscscc_func_test_list)
logging.debug("begin nscscc func test")
for tp in nscscc_func_test_list:
    tp_path = os.path.join(nscscc_func_test_dir, tp)
    run_single_test(tp_path)
logging.debug("all nscscc func test pass")

nscscc_perf_test_num = len(nscscc_perf_list)
nscscc_perf_ipc_accum = 0
logging.debug("begin nscscc perf test")
for tp in nscscc_perf_list:
    tp_path = os.path.join(nscscc_perf_dir, tp, "inst_data.bin")
    log = run_single_test(tp_path)
    ipc_all = re.findall(r"IPC = [0-9].*[0-9]", log)
    ipc = re.findall(r"[0-9].*[0-9]", str(ipc_all))[-1]
    print(tp + " ipc : " + str(ipc))
    nscscc_perf_ipc_accum = nscscc_perf_ipc_accum + float(ipc)
logging.debug("all nscscc perf test pass, average ipc : " + str(nscscc_perf_ipc_accum / nscscc_perf_test_num))    

# apps
logging.debug("begin apps test")
for tp in apps_list:
    tp_path = os.path.join(apps_dir, tp, "build", tp +  "-" + ISA + "-" + PLATFORM + ".bin")
    run_single_test(tp_path)
logging.debug("all apps pass")
        

runlog.close()