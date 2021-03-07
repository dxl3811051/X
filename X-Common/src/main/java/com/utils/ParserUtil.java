package com.utils;

import com.entity.*;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ParserUtil {
    // 前端的parser类型名和具体DO的映射关系
    public static Map<String, Class<? extends NewsParserDO>> parserMapping = new ConcurrentHashMap<>();
    //用于ParserDO器的反序列化
    public static RuntimeTypeAdapterFactory<NewsParserDO> typeAdapter;

    static {
        parserMapping.put("IndexParser", IndexParserDO.class);
        parserMapping.put("电子报Parser", EpaperParserDO.class);
        parserMapping.put("PageParser", PageParserDO.class);
        parserMapping.put("CustomParser", CustomParserDO.class);

        //填充对应的type关系,用于编码编辑的json反序列化
        typeAdapter = RuntimeTypeAdapterFactory.of(NewsParserDO.class, "type");
        parserMapping.forEach((name, clazz) -> typeAdapter.registerSubtype(clazz, name));
    }
}
