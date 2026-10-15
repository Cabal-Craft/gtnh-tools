"""Pack the filtered dust recipes into the compact data.json the page loads.

usage: python -I build_data.py <recipedump dir> <dust.json> <out dir>
Node index == line index in icons_wanted.txt == icon index in the atlas.
"""
import json, os, sys, re

D, DUST, OUT = sys.argv[1:4]
os.makedirs(OUT, exist_ok=True)
I = json.load(open(os.path.join(D, 'items.json'), encoding='utf-8'))
d = json.load(open(DUST, encoding='utf-8'))
items = {i['k']: i for i in I['items']}
fluids = {f['k']: f for f in I['fluids']}
order = [l.strip() for l in open(os.path.join(D, 'icons_wanted.txt'), encoding='utf-8') if l.strip()]
idx = {k: n for n, k in enumerate(order)}

PREFIX_FROM_ORE = re.compile(r'^(dustTiny|dustSmall|dustImpure|dustPure|dustRefined|dust)[A-Z]')


def prefix_of(i):
    p = i.get('prefix')
    if p: return p
    for o in i.get('ore', []):
        m = PREFIX_FROM_ORE.match(o)
        if m: return m.group(1)
    return ''


nodes = []
for k in order:
    if k.startswith('fluid:'):
        f = fluids.get(k[6:], {'name': k[6:]})
        nodes.append({'k': k, 'n': f['name'], 'f': 1, **({'g': 1} if f.get('gas') else {}),
                      **({'T': f['temp']} if f.get('temp') else {})})
        continue
    i = items.get(k, {'name': k})
    rec = {'k': k, 'n': i['name']}
    p = prefix_of(i)
    if p: rec['p'] = p
    if i.get('formula') and i['formula'] != '?': rec['fo'] = i['formula']
    if i.get('mat'): rec['m'] = i['mat']
    if i.get('ore'): rec['o'] = i['ore'][:6]
    tip = [t for t in i.get('tip', []) if t and t != i.get('formula')][:8]
    if tip: rec['t'] = tip
    nodes.append(rec)

machines = list(d['machines'].items())
midx = {m: n for n, (m, _) in enumerate(machines)}
recipes = []
for r in d['recipes']:
    def stacks(lst, fl):
        out = []
        for x in lst:
            k = ('fluid:' + x[0]) if fl else x[0]
            if k not in idx: continue
            s = [idx[k], x[1]]
            if len(x) > 2:
                if isinstance(x[2], str): s += [10000, x[2]]  # crafting ore-dict name
                else: s.append(x[2])
            out.append(s)
        return out
    rec = [midx[r['m']], stacks(r['i'], False) + stacks(r['fi'], True), stacks(r['o'], False) + stacks(r['fo'], True),
           r['eu'], r['t']]
    if r.get('sv'): rec.append(r['sv'])
    recipes.append(rec)

json.dump({'version': 'GTNH 2.9.0-RC1 · GT5U 5.09.54.183', 'machines': machines, 'nodes': nodes, 'recipes': recipes},
          open(os.path.join(OUT, 'data.json'), 'w', encoding='utf-8'), ensure_ascii=False, separators=(',', ':'))
print('nodes', len(nodes), 'recipes', len(recipes), 'bytes', os.path.getsize(os.path.join(OUT, 'data.json')))
