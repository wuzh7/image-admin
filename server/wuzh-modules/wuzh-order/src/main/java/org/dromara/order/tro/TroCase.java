package org.dromara.order.tro;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Data
@TableName(value = "tro_case", autoResultMap = true)
public class TroCase {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String caseNoFormat;
    private String caseName;
    private String court;
    private String judge;
    private String description;
    private String natureOfSuit;
    private String cause;
    private LocalDate dateFiled;
    private String plaintiff;
    private String plaintiffCn;
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> plaintiffNames;
    private String lawyer;
    private String lawFirms;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
