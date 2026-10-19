export function installAudioUi({getCsrf,getLanguage,getPage,message,perform}){
 const pick=(en,ko)=>getLanguage()==='ko'?ko:en,limit=25*1024*1024;
 let hash='',pending=null,capture=null,draftEpoch=0,attempt=0,upload=null,attachment=0;
 const urls=new Set(),form=document.getElementById('note-form'),box=document.createElement('div');box.className='audio-draft';
 const record=document.createElement('button'),remove=document.createElement('button'),file=document.createElement('input'),status=document.createElement('p');
 record.type=remove.type='button';record.className=remove.className='secondary';file.type='file';file.accept='audio/*,.m4a,.webm,.mp4,.ogg,.wav,.mp3';status.className='muted';
 box.append(record,file,remove,status);form.insertBefore(box,form.querySelector('.form-actions'));
 function labels(){record.textContent=capture?pick('Stop recording','녹음 정지'):pick('Record voice','음성 녹음');remove.textContent=pick('Remove voice attachment','음성 첨부 제거');file.setAttribute('aria-label',pick('Attach an audio file, up to 25 MiB','최대 25 MiB 음성 파일 첨부'));status.textContent=capture?pick('Recording · maximum 5 minutes','녹음 중 · 최대 5분'):pending?pick('Audio upload pending. Save to retry.','음성 업로드 대기 중입니다. 저장을 눌러 재시도하세요.'):hash?pick('Voice attached · save the note to keep it','음성 첨부됨 · 메모를 저장하세요'):pick('Optional voice note · up to 25 MiB','음성 메모 선택 첨부 · 최대 25 MiB');}
 function attach(blob,epoch=draftEpoch){const id=++attachment;const current=send(blob,epoch,id);upload=current;current.finally(()=>{if(upload===current)upload=null;}).catch(()=>{});return current;}
 async function send(blob,epoch,id){
  if(!blob.size||blob.size>limit)throw Error(pick('Audio must be 1 byte to 25 MiB.','음성 파일은 1바이트 이상 25 MiB 이하여야 합니다.'));
  if(epoch!==draftEpoch)return;pending=blob;labels();
  const bytes=await blob.arrayBuffer(),sum=await crypto.subtle.digest('SHA-256',bytes),next=[...new Uint8Array(sum)].map(b=>b.toString(16).padStart(2,'0')).join('');
  const result=await fetch('/api/audio/upload/'+next,{method:'POST',headers:{'Content-Type':'application/octet-stream','X-CSRF-Token':getCsrf()},body:bytes});
  if(!result.ok)throw Error(pick('Audio upload failed. Keep this draft and try Save again.','음성 업로드에 실패했습니다. 초안을 유지하고 저장을 다시 눌러보세요.'));
  if(epoch===draftEpoch&&id===attachment){hash=next;pending=null;labels();}
 }
 async function stop(){if(!capture)return;const active=capture;if(active.recorder.state!=='inactive')active.recorder.stop();active.stream.getTracks().forEach(t=>t.stop());return active.done;}
 async function start(){
  if(capture)return stop();if(!navigator.mediaDevices?.getUserMedia||!window.MediaRecorder)throw Error(pick('Recording is unavailable in this browser. Attach an audio file instead.','이 브라우저에서 녹음할 수 없습니다. 음성 파일을 첨부하세요.'));
  const ticket=++attempt,epoch=draftEpoch;record.disabled=true;
  let stream;
  try{
   stream=await navigator.mediaDevices.getUserMedia({audio:true});if(ticket!==attempt||document.hidden||getPage()!=='notes'){stream.getTracks().forEach(t=>t.stop());return;}
   const mime=['audio/mp4','audio/webm;codecs=opus','audio/webm'].find(t=>MediaRecorder.isTypeSupported(t));
   const recorder=new MediaRecorder(stream,mime?{mimeType:mime}:{}),chunks=[];let size=0,resolve,reject;
   const done=new Promise((ok,no)=>{resolve=ok;reject=no;});done.catch(()=>{});
   const active={recorder,stream,done};capture=active;
   const timer=setTimeout(()=>stop().catch(e=>message(e.message,true)),300000);
   recorder.ondataavailable=e=>{if(e.data.size){chunks.push(e.data);size+=e.data.size;if(size>limit)stop().catch(e=>message(e.message,true));}};
   recorder.onerror=()=>{clearTimeout(timer);stream.getTracks().forEach(t=>t.stop());if(capture===active)capture=null;labels();reject(Error(pick('Recording failed. Your text draft is retained.','녹음에 실패했습니다. 텍스트 초안은 유지됩니다.')));};
   recorder.onstop=async()=>{clearTimeout(timer);stream.getTracks().forEach(t=>t.stop());try{await attach(new Blob(chunks,{type:recorder.mimeType}),epoch);resolve();}catch(e){reject(e);}finally{if(capture===active)capture=null;labels();}};
   recorder.start(1000);labels();
  }catch(e){stream?.getTracks().forEach(t=>t.stop());throw e;}finally{record.disabled=false;}
 }
 record.onclick=()=>perform(start);remove.onclick=()=>reset('');file.onchange=()=>{const selected=file.files[0];if(selected)perform(()=>attach(selected));file.value='';};
 function reset(value){draftEpoch++;attempt++;attachment++;upload=null;stop().catch(()=>{});hash=value||'';pending=null;labels();}
 function leave(){attempt++;stop().catch(e=>message(e.message,true));}
 document.addEventListener('visibilitychange',()=>{if(document.hidden)leave();});
 window.addEventListener('beforeunload',()=>{attempt++;capture?.stream.getTracks().forEach(t=>t.stop());});
 function clearPlayers(){for(const url of urls)URL.revokeObjectURL(url);urls.clear();}
 function addPlayer(card,note){if(!note.audio_blob_sha256)return;const button=document.createElement('button');button.type='button';button.className='secondary';button.textContent=pick('Play voice note','음성 메모 재생');button.onclick=()=>perform(async()=>{const result=await fetch('/api/audio/read/'+note.audio_blob_sha256,{method:'POST',headers:{'Content-Type':'application/json','X-CSRF-Token':getCsrf()},body:'{}'});if(!result.ok)throw Error(pick('Audio is unavailable. Sync the source device again.','음성을 가져올 수 없습니다. 원본 기기를 다시 동기화하세요.'));const url=URL.createObjectURL(await result.blob());urls.add(url);const audio=document.createElement('audio');audio.controls=true;audio.src=url;button.replaceWith(audio);await audio.play();});card.append(button);}
 labels();return {labels,reset,leave,clearPlayers,addPlayer,async finish(){attempt++;await stop();if(upload)await upload;if(pending)await attach(pending);return hash;}};
}
