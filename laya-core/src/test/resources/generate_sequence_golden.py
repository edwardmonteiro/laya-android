import json, random, sys
sys.path.insert(0,'/usr/local/lib/python3.11/dist-packages')
from laya.common import build_sequence, render_options
class Tok:
    mask_token="<mask>"; mask_token_id=4; cls_token_id=2; sep_token_id=1; pad_token_id=0
    def __call__(self, text, add_special_tokens=False, truncation=False, max_length=None):
        ids=[1000+ord(c) for c in text]
        if truncation and max_length: ids=ids[:max_length]
        return {"input_ids":ids}
tok=Tok(); random.seed(7)
def rnd(n): return "".join(random.choice("abcdé xyz<mask>") for _ in range(n))
cases=[]
for i in range(60):
    t=random.choice(["choice","score","noul"])
    k=random.choice([2,3,5,12,30])
    if t=="choice": crit={("opt%d_"%j)+rnd(random.randint(0,6)): (None if random.random()<.3 else rnd(random.randint(1,70))) for j in range(k)}
    elif t=="score": crit=[rnd(random.randint(1,70)) for _ in range(k)]
    else: crit=random.choice([None,{"true":rnd(20)},{"false":rnd(10),"true":rnd(80)}])
    q={"t":t,"ins":rnd(random.randint(1,400)),"crit":crit}
    state=rnd(random.choice([0,5,300,1500]))
    ml=random.choice([64,256,1024]); hml=random.choice([48,128,256])
    try:
        ids,m=build_sequence(tok,state,q,ml,hml)
        ok=len(m)==len(render_options(q))
    except Exception as e:
        ids,m,ok=[],[],False
    cases.append({"t":t,"ins":q["ins"],"crit":crit,"state":state,"max_len":ml,"head_max_len":hml,"ids":ids,"markers":m,"ok":ok})
json.dump(cases,open("golden.json","w"),ensure_ascii=False)
print(len(cases), sum(c["ok"] for c in cases))
