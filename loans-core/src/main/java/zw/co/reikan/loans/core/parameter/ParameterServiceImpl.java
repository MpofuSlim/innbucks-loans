package zw.co.reikan.loans.core.parameter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheConfig;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.core.convert.ConversionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@Transactional(readOnly = true)
@Slf4j
@CacheConfig(cacheNames = {"parameterCache"})
public class ParameterServiceImpl implements ParameterService {

    private final ParameterRepository parameterRepository;

    private final ConversionService conversionService;

    public ParameterServiceImpl(ParameterRepository parameterRepository, ConversionService conversionService) {
        this.parameterRepository = parameterRepository;
        this.conversionService = conversionService;
    }

    @Override
    @Cacheable(value = "parameterCache", key = "#root.method.name.concat(':').concat(#parameterName).concat(':').concat(#requiredType.toString())")
    public <T> T getParameterValue(final String parameterName, final Class<T> requiredType) {
        final Parameter parameter = parameterRepository.findByName(parameterName);
        return parameter == null ? null : convertIfNecessary(parameter.getValue(), requiredType);
    }

    @SuppressWarnings("unchecked")
    private <T> T convertIfNecessary(final String value, final Class<T> requiredType) {
        if (value == null) {
            return null;
        }
        if (requiredType == String.class) {
            return (T) value;
        }
        return conversionService.convert(value, requiredType);
    }

    @Override
    @Cacheable(value = "parameterCache")
    public Map<String, String> getParameterValues(final String... parameterNames) {
        final List<Parameter> parameters = parameterRepository.findByNameIn(parameterNames);
        final Map<String, String> parameterMap = new HashMap<>(parameters.size());
        for (final Parameter parameter : parameters) {
            parameterMap.put(parameter.getName(), parameter.getValue());
        }
        return parameterMap;
    }

    @Override
    public List<Parameter> findAll() {
        try {
            return parameterRepository.findAll();
        } catch (Exception e) {
            log.error("Failed to retrieve parameters {}", e);
            return new ArrayList<>();
        }
    }

    @Override
    @Transactional
    @CacheEvict(cacheNames = "parameterCache", allEntries = true)
    public Parameter save(Parameter parameter) {
        return parameterRepository.save(parameter);
    }
}
