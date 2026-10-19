const labels={
 en:{local_only:'Saved on this phone',pending_delivery:'Saved on PC · phone delivery pending',preparing:'Phone preparing audio',armed:'Phone reported scheduled',blocked_permission:'Blocked · phone permission needed',blocked_audio:'Blocked · phone audio unavailable',conflict:'Conflict · review both versions',cancel_pending:'Cancel pending · a phone may still ring',cancelled:'Cancellation confirmed on all known phones',fired:'Triggered on phone',dismissed:'Stopped',snoozed:'Snoozed',missed:'Missed',interrupted:'Playback interrupted'},
 ko:{local_only:'폰에만 저장됨',pending_delivery:'PC 저장됨 · 폰 전달 대기',preparing:'폰 음성 준비 중',armed:'폰 예약 확인됨',blocked_permission:'차단됨 · 폰 권한 필요',blocked_audio:'차단됨 · 폰 음성 준비 안 됨',conflict:'충돌 · 두 버전 검토 필요',cancel_pending:'취소 확인 대기 · 폰이 울릴 수 있음',cancelled:'알려진 모든 폰에서 취소 확인됨',fired:'폰에서 실행됨',dismissed:'정지됨',snoozed:'미뤄짐',missed:'지나친 알람',interrupted:'재생 중단됨'}
};
export function statusLabel(state,lang='en'){return (labels[lang]||labels.en)[state]||state;}
export function scheduleState(entity,acks,{now=Date.now(),deviceIds=[]}={}){
 const rule=entity.payload,all=acks.filter(a=>a.schedule_id===entity.entity_id);
 const matched=all.filter(a=>a.epoch_id===rule.epoch_id&&a.server_revision===entity.server_revision);
 if(!rule.enabled){
  const known=new Set(deviceIds.length?deviceIds:all.map(a=>a.device_id||'_'));
  const confirmed=new Set(matched.filter(a=>a.state==='cancelled').map(a=>a.device_id||'_'));
  return known.size&&[...known].every(id=>confirmed.has(id))?'cancelled':'cancel_pending';
 }
 if(matched.some(a=>a.state==='armed'&&a.capabilities?.exact_alarm==='granted'&&(a.armed_occurrences||[]).some(o=>Date.parse(o.at_utc)>now)))return 'armed';
 const latest=[...matched].sort((a,b)=>(Date.parse(b.observed_at)||0)-(Date.parse(a.observed_at)||0))[0];
 return !latest||latest.state==='armed'?'pending_delivery':latest.state;
}
