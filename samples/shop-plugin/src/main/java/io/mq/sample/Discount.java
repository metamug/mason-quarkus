package io.mq.sample;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

import io.mq.plugin.Plugin;
import io.mq.plugin.PluginRequest;

/**
 * An Execute class: applies a percentage discount to a price that an earlier Sql step found (the Arg named price comes from an mpath),
 * and counts the products through the datasource it is given, to show that a plugin can use JDBC.
 */
public class Discount implements Plugin {

    @Override
    public Object process(PluginRequest request, Map<String, Object> args) throws Exception {
        BigDecimal price = new BigDecimal(String.valueOf(args.get("price")));
        BigDecimal percent = new BigDecimal(String.valueOf(args.get("percent")));
        BigDecimal discounted = price.multiply(BigDecimal.valueOf(100).subtract(percent)).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("price", price);
        out.put("percent", percent);
        out.put("discounted", discounted);
        try (Connection c = request.dataSource().getConnection(); Statement st = c.createStatement();
                ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM product")) {
            rs.next();
            out.put("catalogSize", rs.getInt(1));
        }
        return out;
    }
}
