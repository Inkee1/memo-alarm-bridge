import test from 'node:test';
import assert from 'node:assert/strict';
import {statusLabel, scheduleState} from './state.mjs';
test('pending, armed, blocked and conflict have distinct labels in both languages',()=>{
 for(const lang of ['en','ko']){
  const states=['pending_delivery','armed','blocked_audio','conflict'];
  assert.equal(new Set(states.map(s=>statusLabel(s,lang))).size,4);
 }
 assert.match(statusLabel('pending_delivery','en'),/pending/i);
 assert.match(statusLabel('armed','ko'),/예약됨/);
});
test('an old device acknowledgement never labels a new revision armed',()=>{
 const e={entity_id:'s',server_revision:12,payload:{epoch_id:'new',enabled:true}};
 assert.equal(scheduleState(e,[{schedule_id:'s',epoch_id:'old',server_revision:11,state:'armed'}]),'pending_delivery');
 assert.equal(scheduleState(e,[{schedule_id:'s',epoch_id:'new',server_revision:12,state:'armed'}]),'armed');
 e.payload.enabled=false;
 assert.equal(scheduleState(e,[{schedule_id:'s',epoch_id:'new',server_revision:12,state:'armed'}]),'cancel_pending');
});
