#!/usr/bin/env python3
"""Shape scan for JUnit tests: tests per class, @Test bodies identical once literals are normalised, bodies with no assertion.
Usage: python3 testscan.py [file-or-directory]   (default: src/test/java)"""
import re, sys, os, hashlib, collections, glob, signal
signal.signal(signal.SIGPIPE, signal.SIG_DFL)   # `| head` must not trace back
root='src/test/java'
target=sys.argv[1] if len(sys.argv)>1 else root
files=[target] if target.endswith('.java') else glob.glob(target.rstrip('/')+'/**/*.java', recursive=True)
def methods(src):
    out=[]
    for m in re.finditer(r'@(?:Test|ParameterizedTest)\b[^\n]*\n(?:\s*@\w+[^\n]*\n)*\s*(?:public\s+)?void\s+(\w+)\s*\([^)]*\)\s*(?:throws[^{]*)?\{', src):
        name=m.group(1); i=m.end(); depth=1
        while i<len(src) and depth:
            c=src[i]
            if c=='{': depth+=1
            elif c=='}': depth-=1
            i+=1
        body=src[m.end():i-1]
        line=src.count('\n',0,m.start())+1
        out.append((name,line,body))
    return out
def norm(body):
    b=re.sub(r'//[^\n]*','',body)
    b=re.sub(r'"(?:\\.|[^"\\])*"','"S"',b)
    b=re.sub(r'\b\d+(?:\.\d+)?[LlfFdD]?\b','N',b)
    b=re.sub(r'\s+',' ',b).strip()
    return b
total=0; per=[]; dupgroups=[]; noassert=[]
for f in sorted(files):
    src=open(f).read(); ms=methods(src); total+=len(ms); per.append((len(ms),f))
    groups=collections.defaultdict(list)
    for name,line,body in ms:
        groups[hashlib.md5(norm(body).encode()).hexdigest()].append((name,line))
        if not re.search(r'assert|verify|expect|andExpect|isEqualTo|fail\(|Assertions|then\(|check', body): noassert.append((f,name,line))
    for k,v in groups.items():
        if len(v)>1: dupgroups.append((f,v))
print("test files:",len(files),"test methods:",total)
print("\n=== largest classes ===")
for n,f in sorted(per,reverse=True)[:25]: print(f"{n:5d}  {f.replace(root+'/','')}")
print("\n=== identical bodies modulo literals (same file) ===")
dupcount=0
for f,v in sorted(dupgroups,key=lambda x:-len(x[1])):
    dupcount+=len(v)-1
    print(f"{f.replace(root+'/','')}: {len(v)}x  "+", ".join(f"{n}:{l}" for n,l in v))
print("redundant-by-shape methods:",dupcount)
print("\n=== no assertion/verify in body ===")
for f,n,l in noassert: print(f"{f.replace(root+'/','')}:{l} {n}")
