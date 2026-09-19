package org.dromara.image.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.dromara.image.domain.InfringementScanTask;

@Mapper
public interface InfringementScanTaskMapper extends BaseMapper<InfringementScanTask> {

    @Delete("DELETE FROM infringement_scan_task")
    int deleteAllForReset();
}
