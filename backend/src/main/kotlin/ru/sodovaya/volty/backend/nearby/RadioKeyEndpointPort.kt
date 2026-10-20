package ru.sodovaya.volty.backend.nearby

sealed interface RadioKeyResult {
    /** [key] is 32 bytes, valid for exactly this [epoch]. */
    data class Issued(val communityId:String,val epoch:Long,val key:ByteArray):RadioKeyResult {
        override fun toString()="Issued(communityId=$communityId, epoch=$epoch)"
        override fun equals(other:Any?)=other is Issued && communityId==other.communityId && epoch==other.epoch && key.contentEquals(other.key)
        override fun hashCode()=31*(31*communityId.hashCode()+epoch.hashCode())+key.contentHashCode()
    }
    data object Denied:RadioKeyResult
}

fun interface RadioKeyEndpointPort {
    fun issue(actor:String,community:String):RadioKeyResult
}
