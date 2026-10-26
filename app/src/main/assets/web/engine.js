// legado 书源规则引擎 JS 版
// 语法: || 备选链 && 串联链 ## 替换链 @css/@json 前缀
function legadoCssToQsa(rule) {
  let attr = 'text';
  const parts = rule.split('@');
  const last = parts[parts.length - 1];
  const attrs = ['text','textNodes','html','href','src','content','all','alt','title','value'];
  if (attrs.includes(last)) { attr = last; parts.pop(); }
  let sel = parts.join(' ') || '*';
  sel = sel.replace(/\bclass\.([\w-]+)/g, '.$1');
  sel = sel.replace(/\bid\.([\w-]+)/g, '#$1');
  sel = sel.replace(/\btag\./g, '');
  sel = sel.replace(/\.(\d+)(?![\w.])/g, (m, idx) => `:nth-of-type(${Number(idx)+1})`);
  sel = sel.replace(/\.(-\d+)/g, (m, idx) => `:nth-last-of-type(${-Number(idx)})`);
  return { selector: sel.trim(), attr };
}

function applyReplace(text, chain) {
  let s = String(text);
  const m = chain.match(/^##([\s\S]*?)(?:##([\s\S]*?))?###?$/);
  if (m) {
    try { return s.replace(new RegExp(m[1], 'g'), m[2] || ''); } catch(e) { return s; }
  }
  return s;
}

function nodeValue(el, attr) {
  if (!el) return '';
  if (attr === 'text') return (el.textContent || '').trim();
  if (attr === 'textNodes') return Array.from(el.childNodes).filter(n => n.nodeType === 3).map(n => n.textContent.trim()).join('\n');
  if (attr === 'html') return el.innerHTML || '';
  return el.getAttribute ? (el.getAttribute(attr) || '') : '';
}

function evalRule(rule, doc, baseUrl) {
  if (!rule) return '';
  const root = doc;
  for (const alt of String(rule).split('||')) {
    const segs = alt.split('&&');
    const out = [];
    let ok = true;
    for (const rawSeg of segs) {
      let seg = rawSeg.trim();
      if (!seg) continue;
      let chain = '';
      const ci = seg.indexOf('##');
      if (ci >= 0) { chain = seg.slice(ci); seg = seg.slice(0, ci).trim(); }
      let val = '';
      if (seg.startsWith('@js:') || seg.startsWith('{{')) { ok = false; break; }
      else {
        let seg2 = seg;
        // 裸属性规则（如 "text" / "href"）: 直接取根元素
        const bareAttrs = ['text','textNodes','html','href','src','content','all','alt','title','value'];
        if (bareAttrs.includes(seg2)) {
          let bv = nodeValue(root, seg2);
          if (['href','src'].includes(seg2) && baseUrl && bv && !/^https?:/.test(bv)) {
            let v2 = bv.startsWith('//') ? 'https:' + bv : bv;
            try { bv = new URL(v2, baseUrl).href; } catch(e) { bv = v2; }
          }
          out.push(bv);
          continue;
        }
        const { selector, attr } = legadoCssToQsa(seg2);
        try {
          let el = null;
          const tm = selector.match(/text\.([^\s:]+)/);
          if (tm) {
            const textWant = tm[1];
            const baseSel = selector.replace(/text\.[^\s]+/g, '').trim() || '*';
            const cands = Array.from(root.querySelectorAll(baseSel));
            el = cands.find(e => (e.textContent || '').includes(textWant)) || null;
          } else {
            el = root.querySelector(selector);
          }
          if (!el) { ok = false; break; }
          val = nodeValue(el, attr);
          if (['href','src'].includes(attr) && baseUrl && val && !/^https?:/.test(val)) {
            let v2 = val.startsWith('//') ? 'https:' + val : val;
            try { val = new URL(v2, baseUrl).href; } catch(e) { val = v2; }
          }
        } catch (e) { ok = false; break; }
      }
      if (chain) val = applyReplace(val, chain);
      out.push(val);
    }
    if (ok && out.length) return out.join('\n');
  }
  return '';
}

function evalList(rule, doc) {
  if (!rule) return [];
  for (const alt of String(rule).split('||')) {
    let seg = alt.split('&&')[0].trim();
    if (!seg || seg.startsWith('@js:') || seg.startsWith('{{')) continue;
    let { selector } = legadoCssToQsa(seg);
    selector = selector.replace(/text\.[^\s]+/g, '').trim();
    if (!selector) selector = '*';
    try {
      const list = Array.from(doc.querySelectorAll(selector));
      if (list.length) return list;
    } catch (e) {}
  }
  return [];
}

if (typeof module !== 'undefined') module.exports = { evalRule, evalList, legadoCssToQsa };
