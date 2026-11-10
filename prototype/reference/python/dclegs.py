import heapq, math
from dcroute import tang
MODELS={"time":dict(kind="time",tL=10,tR=3,tS=30),"dist":dict(kind="dist",tL=0,tR=0,tS=0),"timeL":dict(kind="time",tL=30,tR=5,tS=60)}
def navi_tree(g,start_edge,model,bound=1200.0):
    m=MODELS[model]; lab={}; pred={}; heap=[]
    lab[start_edge]=0.0; pred[start_edge]=-1; heapq.heappush(heap,(0.0,start_edge))
    while heap:
        c,e=heapq.heappop(heap)
        if c>lab.get(e,1e18): continue
        if c>bound: break
        E=g.E[e]
        for e2 in g.out[E["h"]]:
            if e2==E["tw"] or (e,e2) in g.ban: continue
            E2=g.E[e2]; th=tang(E["hout"],E2["hin"])
            if abs(th)>165: continue
            tc=0 if abs(th)<=30 else (m["tS"] if abs(th)>150 else (m["tL"] if th>0 else m["tR"]))
            w=E2["time"] if m["kind"]=="time" else E2["L"]/10.0
            nc=c+w+tc
            if nc<lab.get(e2,1e18): lab[e2]=nc; pred[e2]=e; heapq.heappush(heap,(nc,e2))
    return lab,pred
def reach(g,seq,cur,model,minlen=60.0):
    lab,pred=navi_tree(g,seq[cur],model)
    m=MODELS[model]; best=cur; j=cur+1
    while j<len(seq):
        if pred.get(seq[j])!=seq[j-1]: break
        E=g.E[seq[j]]
        ok = E["L"]>=minlen or j==len(seq)-1
        tw=E["tw"]
        if ok and tw>=0 and tw in lab:
            w=lambda x: g.E[x]["time"] if m["kind"]=="time" else g.E[x]["L"]/10.0
            if not (lab[seq[j]]-0.5*w(seq[j]) < lab[tw]-0.5*w(tw)): ok=False
        if ok: best=j
        j+=1
    return best
def split(g,seq,models=("time","dist","timeL")):
    vias=[]; cur=0; stuck=0
    while cur<len(seq)-1:
        r=min(reach(g,seq,cur,mo) for mo in models)
        if r<=cur:
            r=cur+1; stuck+=1
        vias.append(r); cur=r
    return vias,stuck
