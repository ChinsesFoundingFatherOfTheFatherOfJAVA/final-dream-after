package jack.digityhuman.atualcombattraining.migration;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * AI实战训练场学员端数据迁移执行器（2026-10-30）
 *
 * <p>为什么存在：10-旧数据迁移执行.sql 使用 DELIMITER $$ 定义两个存储过程，
 * 本机没有 mysql CLI，契约 10 §9 明确"生产环境若没有 mysql CLI，使用现有 JDBC 迁移器执行同一 SQL"。
 * 本类就是这个 JDBC 迁移器：解析 markdown 包裹的 SQL 文件、处理 DELIMITER 切换、逐条执行并记录日志。
 *
 * <p>执行顺序（契约 05 §1 / 10 §1）：
 * <ol>
 *   <li>05-建表SQL.md：先建 14 张新表（CREATE TABLE IF NOT EXISTS，可重复执行）</li>
 *   <li>10-旧数据迁移执行.sql：条件加列 → 备份表 → v0 复制 → 指针/设置回填 → 验证</li>
 * </ol>
 *
 * <p>安全约定：
 * <ul>
 *   <li>连接参数从环境变量读取（ACT_DB_HOST/ACT_DB_USER/ACT_DB_PASSWORD），不写死密码；</li>
 *   <li>脚本自带 act_migration_backup_* 备份表，执行前无需额外备份；</li>
 *   <li>执行期间 ACT 应用必须处于停止状态（契约 10 §1.2），由调用方保证。</li>
 * </ul>
 *
 * <p>用法：直接运行 main()，参数为空时按契约默认顺序执行两个文件。
 */
public final class SchemaMigrationRunner {

    /** 执行记录（契约 10 §9 要求落盘的信息） */
    record StepLog(String file, int index, String head, long ms, boolean ok, String error) {
    }

    private final List<StepLog> logs = new ArrayList<>();
    private final Connection connection;

    private SchemaMigrationRunner(Connection connection) {
        this.connection = connection;
    }

    public static void main(String[] args) throws Exception {
        String host = env("ACT_DB_HOST", "192.168.81.10");
        String port = env("ACT_DB_PORT", "3306");
        String db = env("ACT_DB_NAME", "final_dream");
        String user = env("ACT_DB_USER", "root");
        String password = System.getenv("ACT_DB_PASSWORD");
        if (password == null || password.isBlank()) {
            throw new IllegalStateException("ACT_DB_PASSWORD 未设置，拒绝执行迁移");
        }
        String base = System.getenv("MIGRATION_DOC_DIR");
        if (base == null || base.isBlank()) {
            base = "D:/JACK/plan/学员端AI实战训练场";
        }

        String url = "jdbc:mysql://" + host + ":" + port + "/" + db
                + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai"
                + "&forceConnectionTimeZoneToSession=true&allowMultiQueries=false";
        long startedAt = System.currentTimeMillis();
        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            SchemaMigrationRunner runner = new SchemaMigrationRunner(connection);
            // 顺序 1：05 的 14 张新表（CREATE TABLE IF NOT EXISTS，幂等）
            runner.runFile(Path.of(base, "05-建表SQL.md"));
            // 顺序 2：10 的条件加列/备份/v0 复制/指针回填/验证
            runner.runFile(Path.of(base, "10-旧数据迁移执行.sql"));
            runner.printSummary(db, startedAt);
        }
    }

    /** 解析一个 markdown/SQL 文件并逐条执行：处理 ```sql 围栏、DELIMITER 切换和 $$ 过程体。 */
    private void runFile(Path file) throws Exception {
        if (!Files.exists(file)) {
            throw new IllegalStateException("迁移文件不存在：" + file);
        }
        System.out.println("=== 执行 " + file.getFileName() + " ===");
        List<String> statements = parseStatements(Files.readAllLines(file, StandardCharsets.UTF_8));
        if (statements.isEmpty()) {
            throw new IllegalStateException("解析出 0 条语句：" + file + "（解析规则与文件格式不匹配）");
        }
        int index = 0;
        for (String sql : statements) {
            index++;
            String head = sql.replaceAll("\\s+", " ").trim();
            if (head.isEmpty()) {
                continue;
            }
            long t0 = System.currentTimeMillis();
            try (Statement statement = connection.createStatement()) {
                if (head.startsWith("SELECT")) {
                    // 验证查询：逐行打印 check_name / bad_count，任何非 0 都算迁移未通过
                    try (ResultSet rs = statement.executeQuery(sql)) {
                        while (rs.next()) {
                            String checkName = rs.getString(1);
                            long badCount = rs.getLong(2);
                            String verdict = badCount == 0 ? "PASS" : "FAIL";
                            System.out.printf("  [%s] %s = %d%n", verdict, checkName, badCount);
                            if (badCount != 0) {
                                throw new SQLException("迁移验证未通过：" + checkName + " = " + badCount);
                            }
                        }
                    }
                } else {
                    // 05 §0 原则："现有表使用迁移器判断列后再 ALTER，禁止重复执行失败"。
                    // ADD COLUMN 语句先对 information_schema 判断，已存在的列剔除后再执行。
                    String effective = filterExistingColumns(sql);
                    if (effective == null) {
                        System.out.println("  [SKIP] 列已存在：" + head.substring(0, Math.min(70, head.length())));
                        logs.add(new StepLog(file.getFileName().toString(), index,
                                head.substring(0, Math.min(80, head.length())),
                                System.currentTimeMillis() - t0, true, null));
                        continue;
                    }
                    statement.execute(effective);
                }
                logs.add(new StepLog(file.getFileName().toString(), index,
                        head.substring(0, Math.min(80, head.length())),
                        System.currentTimeMillis() - t0, true, null));
            } catch (SQLException e) {
                logs.add(new StepLog(file.getFileName().toString(), index,
                        head.substring(0, Math.min(80, head.length())),
                        System.currentTimeMillis() - t0, false, e.getMessage()));
                throw new SQLException("第 " + index + " 条语句执行失败：" + head, e);
            }
        }
        System.out.println("=== " + file.getFileName() + " 完成，共 " + index + " 条语句 ===");
    }

    /**
     * 把 markdown/SQL 文本切成可执行语句列表。
     *
     * <p>规则：跳过非 ```sql 围栏内容与注释行；DELIMITER $$ 之后以 $$ 结尾的块（存储过程）
     * 整体作为一条语句；其余按分号切分。注释行原样保留在语句内无害，这里直接剥掉以避免
     * "语句以注释开头"在部分驱动下的问题。
     */
    static List<String> parseStatements(List<String> rawLines) {
        List<String> out = new ArrayList<>();
        // 纯 .sql 文件没有 markdown 围栏：整文件都是 SQL；.md 文件只取 ```sql 围栏内容。
        boolean hasFence = rawLines.stream().anyMatch(l -> l.trim().startsWith("```sql"));
        boolean inSqlFence = !hasFence;
        String terminator = ";";
        StringBuilder current = new StringBuilder();
        for (String rawLine : rawLines) {
            String line = stripComment(rawLine);
            String trimmed = line.trim();
            if (!inSqlFence) {
                if (trimmed.startsWith("```sql")) {
                    inSqlFence = true;
                }
                continue;
            }
            if (hasFence && trimmed.startsWith("```")) {
                // sql 围栏结束（无围栏的纯 SQL 文件不退出）
                inSqlFence = false;
                continue;
            }
            if (trimmed.toUpperCase().startsWith("DELIMITER ")) {
                String next = trimmed.substring("DELIMITER ".length()).trim();
                terminator = next.isEmpty() ? ";" : next;
                continue;
            }
            if (trimmed.isEmpty()) {
                continue;
            }
            current.append(line).append('\n');
            if (trimmed.endsWith(terminator)) {
                String statement = current.toString().trim();
                statement = statement.substring(0, statement.length() - terminator.length()).trim();
                if (!statement.isEmpty()) {
                    out.add(statement);
                }
                current.setLength(0);
            }
        }
        String rest = current.toString().trim();
        if (!rest.isEmpty()) {
            out.add(rest);
        }
        return out;
    }

    /**
     * 对 "ALTER TABLE x ADD COLUMN a ..., ADD COLUMN b ..." 形态的语句做幂等处理：
     * 逐列查 information_schema，已存在的剔除；全部已存在返回 null（跳过整句）。
     * 非 ADD COLUMN 语句原样返回。
     */
    private String filterExistingColumns(String sql) throws SQLException {
        String trimmedSql = sql.trim();
        if (trimmedSql.endsWith(";")) {
            trimmedSql = trimmedSql.substring(0, trimmedSql.length() - 1).trim();
        }
        var matcher = java.util.regex.Pattern
                .compile("(?i)ALTER\\s+TABLE\\s+`?(\\w+)`?\\s+((?:ADD\\s+COLUMN\\s+.+)+)$",
                        java.util.regex.Pattern.DOTALL)
                .matcher(trimmedSql);
        if (!matcher.matches()) {
            return sql;
        }
        String table = matcher.group(1);
        String adds = matcher.group(2);
        // 按 ADD COLUMN 切分，保留每段的完整定义
        var pieces = java.util.Arrays.stream(adds.split("(?i)ADD\\s+COLUMN"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        List<String> missing = new ArrayList<>();
        for (String piece : pieces) {
            // 末尾可能有分号
            String def = piece.endsWith(";") ? piece.substring(0, piece.length() - 1).trim() : piece;
            var nameMatcher = java.util.regex.Pattern.compile("^`(\\w+)`\\s|^([A-Za-z0-9_]+)\\s")
                    .matcher(def);
            if (!nameMatcher.find()) {
                // 无法识别列名的定义保持原样，交由数据库报错
                missing.add(def);
                continue;
            }
            String column = nameMatcher.group(1) != null ? nameMatcher.group(1) : nameMatcher.group(2);
            if (!columnExists(table, column)) {
                missing.add(def);
            }
        }
        if (missing.isEmpty()) {
            return null;
        }
        if (missing.size() == pieces.size()) {
            return sql;
        }
        return "ALTER TABLE " + table + " ADD COLUMN " + String.join(", ADD COLUMN ", missing);
    }

    private boolean columnExists(String table, String column) throws SQLException {
        try (ResultSet rs = connection.createStatement().executeQuery(
                "select count(*) from information_schema.COLUMNS "
                        + "where TABLE_SCHEMA = database() and TABLE_NAME = '" + table
                        + "' and COLUMN_NAME = '" + column + "'")) {
            rs.next();
            return rs.getLong(1) > 0;
        }
    }

    /** 剥掉独立注释行与行尾 -- 注释（保留字符串字面量中的 -- 不处理：契约 SQL 中不存在该形态）。 */
    private static String stripComment(String line) {
        String trimmed = line.trim();
        if (trimmed.startsWith("--") || trimmed.startsWith("#")) {
            return "";
        }
        int idx = line.indexOf("--");
        return idx >= 0 ? line.substring(0, idx) : line;
    }

    private void printSummary(String db, long startedAt) {
        System.out.println("=== 迁移汇总 ===");
        long failed = logs.stream().filter(l -> !l.ok).count();
        System.out.printf("db=%s steps=%d failed=%d elapsed=%dms%n",
                db, logs.size(), failed, System.currentTimeMillis() - startedAt);
        for (StepLog log : logs) {
            if (!log.ok) {
                System.out.printf("FAILED #%d %s: %s%n", log.index(), log.head(), log.error());
            }
        }
        if (failed > 0) {
            throw new IllegalStateException("迁移存在失败步骤，见上方日志");
        }
        // 迁移后的行数快照（契约 10 §9：affected counts）
        String[] tables = {
                "act_theme", "act_theme_version", "act_theme_settings",
                "act_product_profile", "act_theme_participant",
                "act_practice_attempt", "act_practice_report"};
        for (String table : tables) {
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery("select count(*) from " + table)) {
                rs.next();
                System.out.printf("rows %s = %d%n", table, rs.getLong(1));
            } catch (SQLException e) {
                System.out.println("rows " + table + " 查询失败: " + e.getMessage());
            }
        }
    }

    private static String env(String key, String def) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? def : value;
    }
}
