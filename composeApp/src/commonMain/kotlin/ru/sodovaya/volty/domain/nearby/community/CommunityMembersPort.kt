package ru.sodovaya.volty.domain.nearby.community

data class CommunityMemberSummary(val userId:String,val displayName:String,val joinedAtMillis:Long)
data class CommunityMembersPage(val communityId:String,val ownerId:String,val items:List<CommunityMemberSummary>)

/** Owner-authorized eligible membership. A listed row never grants a later departure. */
interface CommunityMembersPort {
    suspend fun list(community:String,after:String?=null,limit:Int=20):CommunityResult<CommunityMembersPage>
}
