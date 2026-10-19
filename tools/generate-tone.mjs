// Original five-second fallback tone, generated from sine waves; MIT licensed.
// No speech or user data is included in this Direct Boot resource.
import {mkdirSync, writeFileSync} from 'node:fs';
import {fileURLToPath} from 'node:url';
const rate=16000, frames=rate*5, bytes=frames*2;
const wav=Buffer.alloc(44+bytes);
wav.write('RIFF');wav.writeUInt32LE(36+bytes,4);wav.write('WAVEfmt ',8);
wav.writeUInt32LE(16,16);wav.writeUInt16LE(1,20);wav.writeUInt16LE(1,22);
wav.writeUInt32LE(rate,24);wav.writeUInt32LE(rate*2,28);wav.writeUInt16LE(2,32);wav.writeUInt16LE(16,34);
wav.write('data',36);wav.writeUInt32LE(bytes,40);
for(let n=0;n<frames;n++){const t=n/rate,beat=t%1;const envelope=beat<.6?Math.min(1,beat/.02,(.6-beat)/.05):0;const sample=(Math.sin(2*Math.PI*660*t)+.3*Math.sin(2*Math.PI*880*t))*.18*envelope;wav.writeInt16LE(Math.round(sample*32767),44+n*2);}
const dir=fileURLToPath(new URL('../android/app/src/main/res/raw/',import.meta.url));
mkdirSync(dir,{recursive:true});writeFileSync(dir+'generic_alarm.wav',wav);
