package org.dromara.image.controller;

import cn.dev33.satoken.annotation.SaIgnore;
import lombok.RequiredArgsConstructor;
import org.dromara.image.service.StoreProbeService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/i/q")
public class StoreProbeController {
    private final StoreProbeService service;

    @SaIgnore
    @GetMapping("/r")
    public boolean r(@RequestParam(value = "store_id", required = false) String storeId) {
        return service.contains(storeId);
    }

}
