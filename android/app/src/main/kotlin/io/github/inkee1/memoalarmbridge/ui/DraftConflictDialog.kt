package io.github.inkee1.memoalarmbridge.ui

import android.app.AlertDialog
import com.google.gson.JsonObject
import io.github.inkee1.memoalarmbridge.core.LocalRecord
import io.github.inkee1.memoalarmbridge.core.describeConflictVersion
import java.util.Locale

fun reviewChangedDraft(host:MainActivity,entityType:String,draft:JsonObject,current:LocalRecord?,loadSaved:(LocalRecord)->Unit,saveCopy:()->Unit){
    val korean=Locale.getDefault().language=="ko"
    val description=host.tr("Your draft was kept. The saved item changed while you were editing.\n\nYour draft:\n","초안을 유지했습니다. 편집하는 동안 저장된 항목이 변경됐습니다.\n\n내 초안:\n")+
        describeConflictVersion(entityType,draft,false,korean)+host.tr("\n\nCurrently saved:\n","\n\n현재 저장된 버전:\n")+
        (current?.let{describeConflictVersion(it.entityType,it.payload,it.deleted,korean)}?:host.tr("No saved item","저장된 항목 없음"))+
        host.tr("\n\nSave as new keeps the saved version and creates a separate item.","\n\n새 항목으로 저장하면 현재 저장된 버전을 유지하고 별도 항목을 만듭니다.")
    val dialog=AlertDialog.Builder(host).setTitle(host.tr("Review changed item","변경된 항목 검토")).setMessage(description)
        .setNeutralButton(host.tr("Keep editing draft","초안 계속 편집"),null)
        .setPositiveButton(host.tr("Save as new","새 항목으로 저장")){_,_->runCatching{saveCopy()}.onFailure(host::error)}
    if(current!=null&&!current.deleted)dialog.setNegativeButton(host.tr("Load saved version","저장된 버전 불러오기")){_,_->runCatching{loadSaved(current)}.onFailure(host::error)}
    dialog.show()
}
