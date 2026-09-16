const $=id=>document.getElementById(id);
let session=JSON.parse(sessionStorage.getItem('patSession')||'null'), catalog, visits=[];
const message=s=>$('message').textContent=s;
async function api(path,method='GET',body,retry=true){
  const r=await fetch('/api'+path,{method,headers:{'Content-Type':'application/json',...(session?{Authorization:'Bearer '+session.access_token}:{})},body:body?JSON.stringify(body):undefined});
  if(r.status===401&&session&&retry){const refresh=await fetch('/api/refresh',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({refresh_token:session.refresh_token})});if(refresh.ok){const next=await refresh.json();session={...session,...next,refresh_token:session.refresh_token};sessionStorage.setItem('patSession',JSON.stringify(session));return api(path,method,body,false)}}
  if(!r.ok)throw new Error(await r.text());return r;
}
async function json(...a){return(await api(...a)).json()}
function el(tag,text){const n=document.createElement(tag);if(text!==undefined)n.textContent=text;return n}
function button(text,fn){const n=el('button',text);n.onclick=()=>run(fn);return n}
async function run(fn){try{message('');await fn()}catch(e){message(e.message)}}
function table(parent,headers,rows){const wrap=el('div');wrap.className='scroll';const t=el('table'),head=el('tr');headers.forEach(h=>head.append(el('th',h)));t.append(head);rows.forEach(r=>{const tr=el('tr');r.forEach(v=>{const td=el('td');v instanceof Node?td.append(v):td.textContent=v??'—';tr.append(td)});t.append(tr)});wrap.append(t);parent.append(wrap)}
function showTab(name){['visits','dashboard','reports','settings'].forEach(x=>$(x).hidden=x!==name)}
function time(s){return s?new Intl.DateTimeFormat('it-IT',{dateStyle:'short',timeStyle:'short',timeZone:'Europe/Rome'}).format(new Date(s)):'—'}
function renderVisits(){const q=$('search').value.toLowerCase();$('visitTable').replaceChildren();table($('visitTable'),['Collettore','Pozzetto','Operatore','Completato','Operativo','GPS','Revisione','Dettaglio'],visits.filter(r=>JSON.stringify(r).toLowerCase().includes(q)&&(!$('gps').value||r.gps===$('gps').value)&&(!$('status').value||r.status===$('status').value)).map(r=>[r.collector,r.manhole,r.user,time(r.completed_at),r.status,r.gps,r.review,button('Apri',()=>detail(r.inspection_id))]))}
const sheetLabels={accessible:'Accessibilità',opened:'Apertura dichiarata',no_open_reason:'Motivo della mancata apertura',unsafe:'Controllo non eseguibile in sicurezza',cover:'Botola e telaio',deposits:'Pulizia e depositi',flow:'Deflusso e ostruzioni',walls:'Pareti e canalette',damage:'Infiltrazioni, radici e danni',cleaning:'Pulizia eseguita',closure:'Richiusura',restored:'Ripristino area',anomaly_note:'Anomalia',priority:'Priorità proposta',technical_value:'Materiali e dimensioni',technical_origin:'Origine del dato tecnico',notes:'Note',exception_reason:'Motivazione eccezione GPS',map_position_wrong:'Posizione cartografica probabilmente errata'};
function displayValue(v){return v===null?'Non indicato':v===true?'Sì':v===false?'No':String(v)}
async function detail(id){
 const d=await json('/inspections/'+id),p=d.reference;
 $('detail').replaceChildren(el('h3','Storico del controllo · '+p.collectors.join(' | ')+' / '+p.code),el('p','Posizione cartografica della versione visitata: '+p.latitude+', '+p.longitude));
 d.revisions.forEach(r=>{
  const box=el('details'),v=r.payload;
  box.append(el('summary','Revisione '+r.number+' · '+time(r.received_at)+' · '+r.review));
  box.append(el('p','Motivo della revisione: '+(r.reason||'Registrazione iniziale')));
  table(box,['Voce','Dato'],[['Stato operativo',v.status],['Inizio dichiarato',time(v.started_at)],['Completamento originario',time(v.completed_at)],['Ricezione revisione',time(r.received_at)],...Object.entries(v.sheet).map(([k,val])=>[sheetLabels[k]||k,displayValue(val)])]);
  box.append(el('h4','Eventi GPS originari'));
  table(box,['Acquisito','Dispositivo: latitudine, longitudine','Accuratezza','Età misura','Valutazione locale'],v.events.map(e=>[time(e.acquired_at),e.latitude===null?'Non disponibile':e.latitude+', '+e.longitude,e.accuracy_m===null?'—':e.accuracy_m+' m',e.age_s===null?'—':e.age_s+' s',e.local_evaluation.state]));
  box.append(el('p','Ricalcolo server: '+r.server_evaluation.state+' · '+r.server_evaluation.reasons.join('; ')));
  const note=el('input');note.placeholder='Nota di verifica documentale';box.append(note);
  ['VERIFICATA_DOCUMENTALMENTE','INTEGRAZIONE_RICHIESTA'].forEach(state=>box.append(button(state==='VERIFICATA_DOCUMENTALMENTE'?'Verificata documentalmente':'Richiedi integrazione',async()=>{await json('/inspections/'+id+'/review','POST',{revision:r.number,state,note:note.value});await detail(id);await reload()})));
  $('detail').append(box);
 });
 $('detail').append(el('small','Identificativo del controllo: '+id));
}

async function reload(){
 const area=$('area').value;if(!area)return;
 const data=catalog.datasets.find(d=>d.area_id===area);if(data.synthetic)message('AREA DIMOSTRATIVA — manufatti e base cartografica sintetici.');
 visits=await json('/inspections?area='+encodeURIComponent(area));renderVisits();
 const options=await json('/reference-options?area='+encodeURIComponent(area));$('deadlineCompany').replaceChildren(...options.companies.map(c=>{const o=el('option',c.name);o.value=c.id;return o}));const d=await json('/dashboard?area='+encodeURIComponent(area));$('summary').replaceChildren(el('p',d.without_records.length+' pozzetti senza registrazioni · '+d.gps_to_review.length+' riscontri GPS da verificare'));
 const no=el('details');no.append(el('summary','Pozzetti senza registrazioni'));table(no,['Collettore','Pozzetto'],d.without_records.map(p=>[p.collectors.join(' | '),p.code]));$('summary').append(no);
 $('summary').append(el('h3','Scadenze'));if(!d.deadlines.length)$('summary').append(el('p','Periodicità/scadenza non configurata'));
 table($('summary'),['Pozzetto','Termine','Completamento','Esito'],d.deadlines.map(x=>[x.manhole_label,time(x.due_at),time(x.completion),x.outstanding?'SCADUTA':x.late?'COMPLETATA TARDIVAMENTE':x.completion?'ENTRO TERMINE':'ASSEGNATA']));
 $('summary').append(el('h3','Anomalie'));d.anomalies.forEach(a=>{const box=el('details');box.append(el('summary',a.state+' · '+a.priority+' · '+a.description),el('pre',JSON.stringify(a.history,null,2)));const note=el('input');note.placeholder='Nota di risoluzione / riapertura';box.append(note,button(a.state==='APERTA'?'Chiudi anomalia':'Riapri',async()=>{await json('/anomalies/'+a.id,'POST',{state:a.state==='APERTA'?'CHIUSA':'APERTA',note:note.value});await reload()}));$('summary').append(box)});
 const pack=await json('/datasets/'+data.id);$('deadlinePoint').replaceChildren(...pack.points.map(p=>{const o=el('option',p.collectors.join(' | ')+' / '+p.code);o.value=p.id;return o}));
 const reports=await json('/reports?area='+encodeURIComponent(area));$('reportList').replaceChildren();table($('reportList'),['Periodo','Estratto (Roma)','Versione','Download'],reports.map(r=>{const links=el('div');['xlsx','csv','json'].forEach(f=>links.append(button(f.toUpperCase(),async()=>{const response=await api('/reports/'+r.id+'/'+f);const u=URL.createObjectURL(await response.blob());const a=el('a');a.href=u;a.download=r.period+'-'+r.id+'.'+f;a.click();setTimeout(()=>URL.revokeObjectURL(u),1000)})));return[r.period,time(r.extracted_at),r.id,links]}));
 if(session.role==='admin'){const u=await json('/users');$('users').replaceChildren();table($('users'),['Utente','Ruolo','Ambiti','Abilitato','Azione'],u.users.map(x=>[x.username,x.role,x.areas.join(', '),String(x.active),userActions(x,u.areas)]));$('company').replaceChildren(...u.companies.map(c=>{const o=el('option',c.name);o.value=c.id;return o}));$('deadlineCompany').value=session.company_id;$('rule').value=JSON.stringify(catalog.rule,null,2)}
}
async function start(){if(!session)return;if(session.role==='operaio')throw Error('Il portale richiede un referente/verificatore o amministratore.');$('login').hidden=true;$('workspace').hidden=false;$('logout').hidden=false;catalog=await json('/catalog');$('area').replaceChildren(...catalog.datasets.map(d=>{const o=el('option',d.name+(d.synthetic?' — SINTETICO':''));o.value=d.area_id;return o}));document.querySelector('[data-tab=settings]').hidden=session.role!=='admin';await reload()}
$('login').onsubmit=e=>{e.preventDefault();run(async()=>{session=await json('/login','POST',{username:$('username').value,password:$('password').value,device_id:'web-admin-'+crypto.randomUUID()});$('password').value='';sessionStorage.setItem('patSession',JSON.stringify(session));await start()})};
$('logout').onclick=()=>run(async()=>{try{await api('/logout','POST')}finally{sessionStorage.removeItem('patSession');location.reload()}});
$('reload').onclick=()=>run(reload);$('area').onchange=()=>run(reload);$('print').onclick=()=>window.print();['search','gps','status'].forEach(x=>$(x).oninput=renderVisits);document.querySelectorAll('[data-tab]').forEach(b=>b.onclick=()=>showTab(b.dataset.tab));
$('deadline').onsubmit=e=>{e.preventDefault();run(async()=>{await json('/deadlines','POST',{manhole_id:$('deadlinePoint').value,company_id:$('deadlineCompany').value,due_at:$('due').value,source:$('source').value});await reload()})};
$('report').onsubmit=e=>{e.preventDefault();run(async()=>{const a=$('area').value;await json('/reports?area='+encodeURIComponent(a)+'&year='+$('year').value+'&quarter='+$('quarter').value+'&synthetic='+catalog.datasets.find(d=>d.area_id===a).synthetic,'POST');await reload()})};
$('newUser').onsubmit=e=>{e.preventDefault();run(async()=>{await json('/users','POST',{username:$('newUsername').value,password:$('newPassword').value,role:$('role').value,company_id:$('company').value,areas:[$('area').value]});$('newPassword').value='';await reload()})};
$('ruleForm').onsubmit=e=>{e.preventDefault();run(async()=>{await json('/rules','POST',JSON.parse($('rule').value));catalog=await json('/catalog');await reload()})};
run(start);

function userActions(user,areas){
 const box=el('div');
 box.append(button('Ambiti',async()=>{
  const dialog=el('dialog');dialog.append(el('h3','Ambiti di '+user.username));const checks=[];
  areas.forEach(area=>{const label=el('label',area.name);const check=el('input');check.type='checkbox';check.value=area.id;check.checked=user.areas.includes(area.id);checks.push(check);label.prepend(check);dialog.append(label)});
  dialog.append(button('Salva ambiti',async()=>{await json('/users/'+user.id+'/grants','PUT',{areas:checks.filter(c=>c.checked).map(c=>c.value)});dialog.close();dialog.remove();await reload()}),button('Annulla',async()=>{dialog.close();dialog.remove()}));document.body.append(dialog);dialog.showModal();
 }));
 if(user.active&&user.id!==session.user_id)box.append(button('Disabilita',async()=>{await json('/users/'+user.id+'/disable','POST');await reload()}));
 if(!user.active)box.append(button('Riabilita',async()=>{const note=window.prompt('Motivo della riabilitazione');if(!note)return;await json('/users/'+user.id+'/enable','POST',{note});await reload()}));
 return box;
}
