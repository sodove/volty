package ru.sodovaya.volty.backend.nearby

data class CommunityMember(val userId:String,val displayName:String,val joinedAtMillis:Long)
sealed interface CommunityMembersResult {
    data class Listed(val communityId:String,val ownerId:String,val items:List<CommunityMember>):CommunityMembersResult
    data object Denied:CommunityMembersResult
}
fun interface CommunityMembersEndpointPort {
    fun list(actor:String,community:String,after:String?,limit:Int):CommunityMembersResult
}
