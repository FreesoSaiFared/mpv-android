const $=id=>document.getElementById(id);
const defaults={server:"https://i9es-home.rattlesnake-shiner.ts.net:19884",token:""};
const info=s=>$("info").textContent=s;
const storage=chrome.storage.local;
storage.get(defaults,x=>{$("server").value=x.server;$("token").value=x.token});
$("start").value=0;$("end").value=60;
$("save").onclick=()=>storage.set({server:$("server").value.replace(/\/$/,""),token:$("token").value},
 ()=>info("Connection saved on this Chrome profile"));
async function tab(){
 const [t]=await chrome.tabs.query({active:true,currentWindow:true});
 if(!t||!t.id)throw Error("No active tab");
 return t;
}
async function position(){
 const t=await tab();
 const [r]=await chrome.scripting.executeScript({target:{tabId:t.id},
   func:()=>document.querySelector("video")?.currentTime??null});
 if(typeof r.result!=="number")throw Error("No HTML5 video in active tab");
 return r.result;
}
$("startNow").onclick=async()=>{try{$("start").value=(await position()).toFixed(1)}
 catch(e){info(e.message)}};
$("endNow").onclick=async()=>{try{$("end").value=(await position()).toFixed(1)}
 catch(e){info(e.message)}};
$("queue").onclick=async()=>{
 try {
  const t=await tab();if(!/^https:\/\/(www\.)?youtube\.com\//.test(t.url||""))
    throw Error("Open a YouTube watch page");
  const start=Number($("start").value),end=Number($("end").value);
  if(!(start>=0&&end>start))throw Error("Invalid clip bounds");
  const endpoint=$("server").value.replace(/\/$/,"");
  const r=await fetch(endpoint+"/api/clips",{method:"POST",
    headers:{"Authorization":"Bearer "+$("token").value,
             "Content-Type":"application/json"},
    body:JSON.stringify({url:t.url,start,end,title:$("title").value||t.title})});
  const body=await r.json();
  if(!r.ok)throw Error(body.error||r.status);
  info("Queued "+body.title+" / "+body.id);
 }catch(e){info("Queue failed: "+e.message)}
};
$("play").onclick=async()=>{
 try{
  const t=await tab(),start=Number($("start").value),end=Number($("end").value);
  if(!(end>start))throw Error("Set valid start/end");
  await chrome.scripting.executeScript({target:{tabId:t.id},
    args:[start,end],func:(a,b)=>{
      const v=document.querySelector("video");if(!v)return;
      if(window.__morningClipStop)v.removeEventListener("timeupdate",window.__morningClipStop);
      v.currentTime=a;window.__morningClipStop=()=>{if(v.currentTime>=b)v.pause()};
      v.addEventListener("timeupdate",window.__morningClipStop);v.play();
    }});
  info("Playing selected fragment");
 }catch(e){info(e.message)}
};
$("studio").onclick=()=>{
 const endpoint=$("server").value.replace(/\/$/,"");
 chrome.tabs.create({url:endpoint+"/studio"});
};
tab().then(t=>{$("title").value=t.title||"";
 info("Page: "+(t.url||"").slice(0,90));
}).catch(e=>info(e.message));
