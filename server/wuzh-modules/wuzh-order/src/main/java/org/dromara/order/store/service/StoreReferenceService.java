package org.dromara.order.store.service;

import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.mybatis.utils.IdGeneratorUtil;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class StoreReferenceService {
    private final JdbcTemplate jdbc;

    public record Option(Long id, String name, String status) {}
    public record Operator(Long id, String name, String account) {}
    public record SaveOption(String name, String status) {}

    private static void checkKind(String kind) {
        if (!"PLATFORM".equals(kind) && !"SITE".equals(kind)) throw new ServiceException("维护类型无效");
    }

    public List<Option> options(String kind, boolean activeOnly) {
        checkKind(kind);
        return jdbc.query("SELECT id,name,status FROM store_reference WHERE kind=?" + (activeOnly ? " AND status='0'" : "") + " ORDER BY id",
            (rs, row) -> new Option(rs.getLong("id"), rs.getString("name"), rs.getString("status")), kind);
    }

    public List<Operator> operators() {
        return jdbc.query("SELECT user_id,nick_name,user_name FROM sys_user WHERE status='0' AND del_flag='0' ORDER BY user_id",
            (rs, row) -> new Operator(rs.getLong("user_id"), rs.getString("nick_name"), rs.getString("user_name")));
    }

    public String optionName(String kind, Long id) {
        checkKind(kind);
        if (id == null) return null;
        return jdbc.query("SELECT name FROM store_reference WHERE kind=? AND id=? AND status='0'", rs -> rs.next() ? rs.getString(1) : null, kind, id);
    }

    public String operatorName(Long id) {
        if (id == null) return null;
        return jdbc.query("SELECT nick_name FROM sys_user WHERE user_id=? AND status='0' AND del_flag='0'", rs -> rs.next() ? rs.getString(1) : null, id);
    }

    @Transactional
    public Option save(String kind, Long id, SaveOption request) {
        checkKind(kind);
        String name = request.name() == null ? "" : request.name().trim();
        if (name.isEmpty() || name.length() > 50 || !("0".equals(request.status()) || "1".equals(request.status())))
            throw new ServiceException("名称或状态无效");
        try {
            if (id == null) {
                id = Long.parseLong(IdGeneratorUtil.nextId());
                jdbc.update("INSERT INTO store_reference(id,kind,name,status) VALUES(?,?,?,?)", id, kind, name, request.status());
            } else if (jdbc.update("UPDATE store_reference SET name=?,status=? WHERE id=? AND kind=?", name, request.status(), id, kind) == 0)
                throw new ServiceException("维护记录不存在");
        } catch (DuplicateKeyException duplicate) { throw new ServiceException("名称已存在"); }
        return new Option(id, name, request.status());
    }

    @Transactional
    public void delete(String kind, long id) {
        checkKind(kind);
        int used = "PLATFORM".equals(kind)
            ? jdbc.queryForObject("SELECT COUNT(*) FROM store_shop WHERE JSON_UNQUOTE(JSON_EXTRACT(config_json,'$.platformId'))=?", Integer.class, String.valueOf(id))
            : jdbc.queryForObject("SELECT COUNT(*) FROM store_shop WHERE JSON_CONTAINS(config_json, ?, '$.siteIds')", Integer.class, String.valueOf(id));
        if (used > 0) throw new ServiceException("已被店铺使用，不能删除，可改为停用");
        if (jdbc.update("DELETE FROM store_reference WHERE id=? AND kind=?", id, kind) == 0) throw new ServiceException("维护记录不存在");
    }
}
