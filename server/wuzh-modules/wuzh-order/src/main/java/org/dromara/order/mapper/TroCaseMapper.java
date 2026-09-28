package org.dromara.order.mapper;

import org.dromara.order.tro.TroCase;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface TroCaseMapper extends BaseMapper<TroCase> {
    @Results(id = "troExternalResult", value = {
        @Result(column = "case_no_format", property = "caseNoFormat"),
        @Result(column = "plaintiff", property = "plaintiff"),
        @Result(column = "plaintiff_names", property = "plaintiffNames", typeHandler = JacksonTypeHandler.class)
    })
    @Select("""
        <script>
        SELECT case_no_format, plaintiff, plaintiff_names
        FROM tro_case
        WHERE 1 = 0
        <if test="plaintiffValues.size() > 0">
          OR <foreach collection="plaintiffValues" item="value" separator=" OR ">LOCATE(LOWER(REPLACE(#{value}, ' ', '')), LOWER(REGEXP_REPLACE(plaintiff, '[[:space:]]+', ''))) > 0</foreach>
          OR EXISTS (SELECT 1 FROM JSON_TABLE(COALESCE(plaintiff_names, JSON_ARRAY()), '$[*]'
            COLUMNS (name VARCHAR(255) PATH '$')) AS names
            WHERE <foreach collection="plaintiffValues" item="value" separator=" OR ">LOCATE(LOWER(REPLACE(#{value}, ' ', '')), LOWER(REGEXP_REPLACE(names.name, '[[:space:]]+', ''))) > 0</foreach>)
        </if>
        <if test="caseNoFormats.size() > 0">
          OR case_no_format IN <foreach collection="caseNoFormats" item="value" open="(" separator="," close=")">#{value}</foreach>
        </if>
        ORDER BY id
        </script>
        """)
    List<TroCase> selectExternal(@Param("plaintiffValues") List<String> plaintiffValues,
                                 @Param("caseNoFormats") List<String> caseNoFormats);

    @Insert("""
        INSERT INTO tro_case (case_no_format, case_name, court, judge, description, nature_of_suit,
            cause, date_filed, plaintiff, plaintiff_cn, plaintiff_names, lawyer, law_firms, create_time, update_time)
        VALUES (#{caseNoFormat}, #{caseName}, #{court}, #{judge}, #{description}, #{natureOfSuit},
            #{cause}, #{dateFiled}, #{plaintiff}, #{plaintiffCn},
            #{plaintiffNames,typeHandler=com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler},
            #{lawyer}, #{lawFirms}, NOW(), NOW())
        ON DUPLICATE KEY UPDATE case_name = VALUES(case_name), court = VALUES(court), judge = VALUES(judge),
            description = VALUES(description), nature_of_suit = VALUES(nature_of_suit), cause = VALUES(cause),
            date_filed = VALUES(date_filed), plaintiff_cn = VALUES(plaintiff_cn),
            plaintiff_names = VALUES(plaintiff_names), lawyer = VALUES(lawyer), law_firms = VALUES(law_firms),
            update_time = NOW()
        """)
    int upsert(TroCase troCase);
}
