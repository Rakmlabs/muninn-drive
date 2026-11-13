package io.coralnut.pleosprobe

/** 취소·새 코드 발급과 승인 저장을 같은 잠금으로 직렬화한다. */
internal class VehicleLinkGeneration {
    private var value=0L
    @Synchronized fun renew():Long=++value
    @Synchronized fun current():Long=value
    @Synchronized fun <T> commit(expected:Long,action:()->T):T?=
        if(expected==value)action()else null
}
