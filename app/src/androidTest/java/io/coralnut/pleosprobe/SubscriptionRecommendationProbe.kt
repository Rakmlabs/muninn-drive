package io.coralnut.pleosprobe

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle

/** 합성 장소로 우선순위 경계를 검증하며 실제 계정의 구독 목록은 변경하지 않는다. */
object SubscriptionRecommendationProbe {
    fun run(test:Instrumentation) {
        val lines=mutableListOf<String>()
        fun place(id:String,km:Double,subscribed:Boolean,channel:String="creator")=
            CoralnutPlace(id,id,channel,channel,channel,"식당","테스트 주소",35.0,129.0,km,"",subscribed)
        try {
            check(isDomesticPlace("부산광역시 수영구 광남로22번길 3",35.1449,129.1107))
            check(isDomesticPlace("대한민국 제주특별자치도 제주시",33.5,126.5))
            check(!isDomesticPlace("일본 Fukuoka",33.59,130.4))
            check(!isDomesticPlace("일본 쓰시마 부산식당",34.2,129.3))
            check(!isDomesticPlace("부산광역시 수영구",35.0,139.0))
            check(!isDomesticPlace("주소 정보 없음",35.1,129.1))
            lines+="PASS domestic addresses and coordinates; foreign/unknown/conflicting locations excluded"
            val ranked=rankRecommendations(listOf(place("near",0.1,false),place("sub-far",19.0,true),place("sub-near",2.0,true)))
            check(ranked.map{it.id}==listOf("sub-near","sub-far","near"))
            lines+="PASS subscribed group before nearer general places; distance within each group"
            val duplicate=rankRecommendations(listOf(place("same",0.2,false,"general"),place("same",0.2,true,"subscribed")))
            check(duplicate.size==1&&duplicate.single().channelId=="subscribed")
            lines+="PASS shared place retains subscribed creator attribution"
            val many=(1..7).map{place("general-$it",it.toDouble(),false)}+place("subscribed",19.0,true)
            val limited=rankRecommendations(many)
            check(limited.size==5&&limited.first().id=="subscribed"&&limited.last().id=="general-4")
            check(rankRecommendations(many.reversed())==limited)
            lines+="PASS top five applied after subscription priority; stable ordering"
            check(rankRecommendations(emptyList()).isEmpty())
            val near=listOf(place("general",1.0,false))
            val noMatch=CoralnutRecommendations(near,"CONNECTED",0)
            check(noMatch.subscriptionLabel=="다른 크리에이터 추천"&&noMatch.subscriptionExplanation.contains("일치하는"))
            val noNearby=CoralnutRecommendations(near,"CONNECTED",2)
            check(noNearby.subscriptionExplanation.contains("20km"))
            check(CoralnutRecommendations(ranked,"CONNECTED",2).subscriptionLabel=="내 구독 크리에이터 추천")
            for(status in listOf("DISABLED","AUTHORIZATION_REQUIRED","UNAVAILABLE","GUEST")) {
                check(!CoralnutRecommendations(near,status).subscriptionLabel.contains("내 구독"))
            }
            lines+="PASS no matched channels, no nearby subscribed places, disabled, missing permission, unavailable and guest labels"
            test.finish(Activity.RESULT_OK,Bundle().apply{putString("stream",lines.joinToString("\n")+"\nSUBSCRIPTION_RANKING_PASS (synthetic data)\n")})
        } catch(error:Throwable) {
            test.finish(Activity.RESULT_CANCELED,Bundle().apply{putString("stream",lines.joinToString("\n")+"\nSUBSCRIPTION_RANKING_FAIL ${error.message}\n")})
        }
    }
}
