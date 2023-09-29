package zw.co.reikan.nanoloansweb.parameter;

import java.util.List;
import java.util.Map;

public interface ParameterService {

    <T> T getParameterValue(String parameterName, Class<T> requiredType);

    Map<String, String> getParameterValues(String... parameterNames);

    List<Parameter> findAll();

     Parameter save(Parameter parameter);
}
