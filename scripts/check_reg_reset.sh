#! /bin/sh

grep -rn "^ *reg " -A1 $1 | sed ":a;N;s/:\n//g;ba" | sed ":a;N;s/--\n//g;ba" | grep -v "reset =>"