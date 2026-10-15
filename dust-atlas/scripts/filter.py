"""Pick every recipe that makes or uses a dust out of a full RecipeDump export.

usage: python -I filter.py <recipedump dir> <out dust.json>
Also writes <recipedump dir>/icons_wanted.txt for the in-game icon pass.
"""
import json,sys,collections,os
D=sys.argv[1]; OUT=sys.argv[2]
R=json.load(open(os.path.join(D,'recipes.json'),encoding='utf-8')); I=json.load(open(os.path.join(D,'items.json'),encoding='utf-8'))
items={i['k']:i for i in I['items']}; fluids={f['k']:f for f in I['fluids']}
def isdust(k):
    i=items.get(k)
    return bool(i) and (i.get('prefix','').startswith('dust') or any(o.startswith('dust') for o in i.get('ore',[])))
def ik(k): # crafting ore keys "ore:x|first"
    return k.split('|',1)[1] if k.startswith('ore:') else k
recipes=[]; machines={}
for m in R['maps']:
    for r in m['recipes']:
        if r.get('fake') and 'fake' in m['id'].lower(): pass
        ins=[x[0] for x in r['in']]; outs=[x[0] for x in r['out']]
        if any(isdust(k) for k in ins+outs):
            machines[m['id']]=m['name']
            recipes.append(dict(m=m['id'],i=r['in'],o=r['out'],fi=r.get('fin',[]),fo=r.get('fout',[]),eu=r['eu'],t=r['t'],sv=r.get('sv')))
for r in R['crafting']:
    ins=[[ik(k),n]+([k.split('|')[0][4:]] if k.startswith('ore:') else []) for k,n in r['in']]
    if any(isdust(x[0]) for x in ins) or isdust(r['o']):
        machines['crafting']='Crafting Table'
        recipes.append(dict(m='crafting',i=ins,o=[[r['o'],r['n']]],fi=[],fo=[],eu=0,t=0))
for r in R['smelting']:
    if isdust(r['i']) or isdust(r['o']):
        machines['smelting']='Furnace'
        recipes.append(dict(m='smelting',i=[[r['i'],1]],o=[[r['o'],r['n']]],fi=[],fo=[],eu=0,t=0))
used=set(); usedf=set()
for r in recipes:
    for x in r['i']+r['o']: used.add(x[0])
    for x in r['fi']+r['fo']: usedf.add(x[0])
print('recipes',len(recipes),'items',len(used),'fluids',len(usedf),'machines',len(machines))
print(collections.Counter(r['m'] for r in recipes).most_common(25))
json.dump(dict(recipes=recipes,machines=machines,items=sorted(used),fluids=sorted(usedf)),open(OUT,'w',encoding='utf-8'))
# the in-game icon pass renders exactly these, in this order (node index == icon index)
open(os.path.join(D,'icons_wanted.txt'),'w',encoding='utf-8').write('
'.join(sorted(used)+['fluid:'+f for f in sorted(usedf)])+'
')
