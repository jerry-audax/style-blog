package com.blogsystem.architecture;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MapperRelocationTest {
    @Test
    void registeredMappersResolveUuidHandlerAfterPackageMove() throws Exception {
        var configuration = new MybatisConfiguration();
        for (String mapper : List.of("asset.MediaAssetMapper", "music.MusicTrackMapper",
                "surprise.SurpriseVideoMapper", "asset.MediaUploadOperationMapper")) {
            Class<?> type = Class.forName("com.blogsystem.infrastructure.mybatis.mapper." + mapper);
            configuration.addMapper(type);
            assertTrue(configuration.getMappedStatementNames().stream()
                    .anyMatch(name -> name.startsWith(type.getName() + ".")), mapper);
        }
        var statement = configuration.getMappedStatement(
                "com.blogsystem.infrastructure.mybatis.mapper.asset.MediaAssetMapper.selectById");
        var parameter = statement.getBoundSql(Map.of("id", UUID.randomUUID()))
                .getParameterMappings().get(0);
        assertEquals("com.blogsystem.infrastructure.mybatis.typehandler.UuidTypeHandler",
                parameter.getTypeHandler().getClass().getName());
    }
}
