package io.github.inkee1.memoalarmbridge.ui

import io.github.inkee1.memoalarmbridge.core.SyncState

fun MainActivity.syncStatus(state:SyncState,key:String):String {
    val record=state.records[key]
    return when {
        record?.conflict!=null->tr("PC conflict · review both versions in Setup","PC와 충돌 · 설정에서 두 버전 검토")
        !state.linked->tr("PC not connected · local data retained","PC 연결 없음 · 폰 데이터 보관 중")
        record?.syncError?.isNotEmpty()==true->tr("PC sync needs attention: ","PC 동기화 확인 필요: ")+record.syncError
        record?.dirty!=false->tr("PC sync pending · phone state shown separately","PC 전송 대기 · 폰 예약 상태는 별도 표시")
        else->tr("Saved on PC · revision ","PC 저장 확인 · 버전 ")+(record.version.serverRevision?:0)
    }
}
