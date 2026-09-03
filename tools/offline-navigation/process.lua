-- Volty's deliberately small mobile map schema.
-- Routing and search are separate package components; this file only creates
-- the PMTiles source consumed by the map renderer.

node_keys = { "amenity", "historic", "leisure", "name", "place", "shop", "tourism" }

function node_function(node)
    local name = node:Find("name:ru")
    if name == "" then name = node:Find("name") end
    if name == "" then name = node:Find("official_name") end
    local amenity = node:Find("amenity")
    local shop = node:Find("shop")
    local place = node:Find("place")

    if amenity ~= "" or shop ~= "" or node:Find("tourism") ~= "" then
        node:Layer("poi", false)
        if amenity ~= "" then node:Attribute("class", amenity)
        elseif shop ~= "" then node:Attribute("class", shop)
        else node:Attribute("class", node:Find("tourism")) end
        if name ~= "" then node:Attribute("name", name) end
        node:AttributeNumeric("rank", 3)
    end

    if place ~= "" then
        node:Layer("place", false)
        node:Attribute("class", place)
        if name ~= "" then node:Attribute("name", name) end
        if place == "city" then
            node:AttributeNumeric("rank", 4)
            node:MinZoom(3)
        elseif place == "town" then
            node:AttributeNumeric("rank", 6)
            node:MinZoom(6)
        else
            node:AttributeNumeric("rank", 9)
            node:MinZoom(10)
        end
    end
end

function way_function(way)
    local highway = way:Find("highway")
    local waterway = way:Find("waterway")
    local building = way:Find("building")
    local landuse = way:Find("landuse")
    local natural = way:Find("natural")
    local name = way:Find("name:ru")
    if name == "" then name = way:Find("name") end
    if name == "" then name = way:Find("official_name") end

    if highway ~= "" then
        way:Layer("transportation", false)
        if highway == "unclassified" or highway == "residential" then highway = "minor" end
        way:Attribute("class", highway)
        local access = way:Find("access")
        if access ~= "" then way:Attribute("access", access) end
        local oneway = way:Find("oneway")
        if oneway ~= "" then way:Attribute("oneway", oneway) end

        if name ~= "" then
            way:Layer("transportation_name", false)
            way:Attribute("class", highway)
            way:Attribute("name", name)
        end
    end

    if waterway == "stream" or waterway == "river" or waterway == "canal" then
        way:Layer("waterway", false)
        way:Attribute("class", waterway)
    end

    local water = way:Find("water")
    if natural == "water" or water ~= "" then
        way:Layer("water", true)
        way:Attribute("class", water ~= "" and water or "lake")
    end

    if building ~= "" then
        way:Layer("building", true)
    end

    if landuse ~= "" then
        way:Layer("landuse", true)
        way:Attribute("class", landuse)
    end
end
