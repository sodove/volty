package ru.sodovaya.volty.presentation.map

/** Small bundled fallback so the camera still has geographic context outside PMTiles coverage. */
internal val WORLD_OVERVIEW_GEOJSON = """
{
  "type":"FeatureCollection",
  "features":[
    {"type":"Feature","properties":{"name":"Евразия"},"geometry":{"type":"Polygon","coordinates":[[[-11,36],[-4,43],[7,44],[14,41],[23,42],[30,47],[38,45],[45,48],[53,56],[63,55],[72,61],[84,67],[101,78],[123,75],[145,71],[164,64],[180,54],[180,72],[151,78],[122,80],[95,78],[70,74],[49,70],[32,67],[18,62],[5,57],[-5,52],[-11,45],[-11,36]]]}},
    {"type":"Feature","properties":{"name":"Африка"},"geometry":{"type":"Polygon","coordinates":[[[-17,35],[-4,37],[7,36],[16,32],[28,31],[39,23],[48,12],[50,-4],[44,-20],[36,-31],[22,-35],[8,-34],[-4,-27],[-12,-10],[-17,7],[-17,35]]]}},
    {"type":"Feature","properties":{"name":"Северная Америка"},"geometry":{"type":"Polygon","coordinates":[[[-168,70],[-150,72],[-135,68],[-122,61],[-110,58],[-100,52],[-88,49],[-80,43],[-70,42],[-63,31],[-72,20],[-84,15],[-97,18],[-111,25],[-121,34],[-134,42],[-150,49],[-163,59],[-168,70]]]}},
    {"type":"Feature","properties":{"name":"Южная Америка"},"geometry":{"type":"Polygon","coordinates":[[[-81,12],[-70,12],[-58,8],[-48,2],[-39,-7],[-42,-22],[-49,-36],[-59,-53],[-70,-55],[-78,-42],[-82,-21],[-81,12]]]}},
    {"type":"Feature","properties":{"name":"Австралия"},"geometry":{"type":"Polygon","coordinates":[[[113,-11],[125,-12],[137,-10],[151,-14],[154,-24],[161,-29],[153,-39],[142,-43],[128,-39],[117,-34],[113,-22],[113,-11]]]}},
    {"type":"Feature","properties":{"name":"Гренландия"},"geometry":{"type":"Polygon","coordinates":[[[-55,82],[-20,82],[-22,60],[-48,60],[-55,82]]]}}
  ]
}
""".trimIndent()
