package dev.meirong.orderservice.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import javax.sql.DataSource;

@Configuration
@Profile("migration")
public class DualWriteConfig {

    @Bean
    @ConfigurationProperties("migration.target-datasource")
    DataSourceProperties migrationTargetDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean(name = "migrationTargetDataSource")
    DataSource migrationTargetDataSource() {
        return migrationTargetDataSourceProperties()
                .initializeDataSourceBuilder()
                .build();
    }

    @Bean
    DualWriteDataSources dualWriteDataSources(
            DataSource dataSource,
            @Qualifier("migrationTargetDataSource") DataSource migrationTargetDataSource
    ) {
        return new DualWriteDataSources(dataSource, migrationTargetDataSource);
    }

    public record DualWriteDataSources(DataSource sharedDataSource, DataSource targetDataSource) {
    }
}
