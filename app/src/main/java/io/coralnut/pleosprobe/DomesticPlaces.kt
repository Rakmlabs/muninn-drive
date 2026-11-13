package io.coralnut.pleosprobe

/** 국가 코드가 없는 현재 공개 API에서는 국내 주소로 확인되는 장소만 허용한다.
 * 좌표 사각형만으로 국가를 판정하지 않는다. 분류 불가 주소는 노출하지 않는다. */
internal fun isDomesticPlace(address:String,latitude:Double,longitude:Double):Boolean {
    if(!latitude.isFinite()||!longitude.isFinite()||latitude !in 32.0..39.0||longitude !in 124.0..132.0)return false
    return hasDomesticAddress(address)
}

internal fun hasDomesticAddress(address:String):Boolean {
    val normalized=address.trim().removePrefix("대한민국").trim().removePrefix("한국").trim()
    val province=normalized.substringBefore(' ')
    return province in domesticProvinces
}

private val domesticProvinces=setOf(
    "서울","서울특별시","부산","부산광역시","대구","대구광역시","인천","인천광역시",
    "광주","광주광역시","대전","대전광역시","울산","울산광역시","세종","세종특별자치시",
    "경기","경기도","강원","강원도","강원특별자치도","충북","충청북도","충남","충청남도",
    "전북","전라북도","전북특별자치도","전남","전라남도","경북","경상북도","경남","경상남도",
    "제주","제주도","제주특별자치도"
)
