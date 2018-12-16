package grace.datastore.entity

import grace.common.Params
import grace.datastore.DB
import groovy.sql.GroovyRowResult
import groovy.sql.Sql
import groovy.util.logging.Slf4j
import java.lang.reflect.Modifier
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 实体 api 实现
 * 实现具体的操作，get save，解析实体类等。
 */
@Slf4j
class EntityImpl {
    //保留变量
    public static final String MAPPING = 'mapping' //mapping 定义实体类与表之间的映射关系,table,columns
    public static final String TRANSIENTS = 'transients' //不持久化的类属性
    public static final String CONSTRAINTS = 'constraints' //约束
    // 系统级忽略的内容
    public static final List<String> excludeProperties = ['metaClass', 'grace_datastore_entity_Entity__errors']

    /**
     * get
     * 神一样的 get
     * @param target
     * @param id
     * @return
     */
    static get(Class target, Serializable id) {
        Sql sql = DB.sql
        String table = findTableName(target)
        def tid = Transformer.toType(target, 'id', id) //pg must transform；mysql not need。
        def result = sql.firstRow("select * from ${table} where id=?", tid)
        def entity = bindResultToEntity(result, target)
        sql.close()
        return entity
    }

    /**
     * list
     * @param target
     * @param params
     * @return
     */
    static list(Class target, Map params) {
        Sql sql = DB.sql
        List list = []
        List rows = sql.rows("select * from ${findTableName(target)} ${EntityUtil.params(params)}".toString())
        rows.each { row ->
            list << bindResultToEntity(row, target)
        }
        sql.close()
        return list
    }

    /**
     * count
     * @param target
     * @return
     */
    static int count(Class target) {
        return DB.withSql { Sql sql -> sql.firstRow("select count(*) as num from ${findTableName(target)}".toString()).num }
    }

    /**
     * save
     * 插入或更新
     * @param entity
     */
    static save(Object entity) {
        Sql sql = DB.sql
        //kvs
        Map columnMap = columnMap(entity.class)
        List ps = findPropertiesToPersist(entity.class)
        Map kvs = [:]
        ps.each {
            def property = entity[it]
            //null，不作处理 todo 这里可能有坑，如果要设置为 null 呢
            if (property != null) {
                //如果是 Date，转换成 LocalDateTime，pg 当前的驱动不支持 Date 了。mysql 无影响。
                if (property instanceof Date) property = java.time.LocalDateTime.ofInstant(property.toInstant(), ZoneId.systemDefault())
                if (columnMap.containsKey(it)) {
                    kvs << [(columnMap[it]): property]
                } else {
                    if (entity[it] instanceof Entity) {
                        kvs << [(EntityUtil.toDbName(it) + '_id'): property.id]
                    } else {
                        kvs << [(EntityUtil.toDbName(it)): property]
                    }
                }
            }
        }
        kvs.remove('id')

        String table = findTableName(entity.class)
        if (entity.hasProperty('id') && entity['id']) {
            //to update
            def sets = kvs.keySet().collect { "${it} = ?" }.join(',').toString()
            def sqlString = "update ${table} set ${sets} where id = ?".toString()
            def params = kvs.values().toList() << entity.id
            sql.executeUpdate(sqlString, params)
        } else {
            //to insert
            def sqlString = "insert into ${table} (${kvs.keySet().join(',')}) values (?${',?' * (kvs.size() - 1)})".toString()
            def result = sql.executeInsert(sqlString, kvs.values().toList())
            entity.id = result[0][0]
        }

        sql.close()
        return entity
    }

    /**
     * delete
     * 删除
     * @param entity
     * @return
     */
    static boolean delete(Object entity) {
        return DB.withSql { Sql sql -> sql.execute "delete from ${findTableName(entity.class)} where id = ?".toString(), [entity.id] }
    }

    /**
     * refresh
     * @param entity
     * @return
     */
    static refresh(Object entity) {
        DB.withSql { Sql sql ->
            def row = sql.firstRow "select * from ${findTableName(entity.class)} where id = ?".toString(), [entity.id]
            bindResultToEntityInstance(row, entity)
        }
    }

    /**
     * 获取持久化属性列表
     * @param target
     * @return
     */
    static List<String> findPropertiesToPersist(Class target) {
        List fields = target.declaredFields.findAll { !Modifier.isStatic(it.modifiers) }*.name
        fields - excludeProperties - (target.hasProperty(TRANSIENTS) ? target[TRANSIENTS] : [])
    }

    /**
     * 获取表名
     * @param target
     * @return
     */
    static String findTableName(Class target) {
        target[MAPPING]?.table ?: EntityUtil.toDbName(target.simpleName)
    }

    /**
     * 获取列映射
     * @param target
     * @return
     */
    static Map columnMap(Class target) {
        target[MAPPING]?.columns ?: [:]
    }

    /**
     * 绑定 result 到 实体类
     * @param result
     * @param target
     * @return
     */
    static bindResultToEntity(GroovyRowResult result, Class target) {
        if (!result) return null

        def entity = target.newInstance()
        return bindResultToEntityInstance(result, entity)
    }

    /**
     * 绑定 row 到 实体实例
     * @param result
     * @param entity
     * @return
     */
    static bindResultToEntityInstance(GroovyRowResult result, Object entity) {
        if (!result) return null

        def clasz = entity.class
        def columnMap = columnMap(clasz)
        List<String> properties = findPropertiesToPersist(clasz)
        properties.each {
            String key = it
            Class propClass = clasz.getDeclaredField(it)?.type
            //处理属性对表列的映射
            if (columnMap.containsKey(key)) {
                key = columnMap[key]
            } else {
                key = EntityUtil.toDbName(key)
                if (propClass.interfaces.contains(Entity)) {
                    key = key + "_id"
                }
            }
            //从 result 中取值并赋值给实体
            if (result.containsKey(key)) {
                switch (propClass) {
                    case LocalDate:
                        entity[(it)] = result[key].toLocalDate()
                        break
                    case LocalDateTime:
                        entity[(it)] = result[key].toLocalDateTime()
                        break
                    case Entity:
                        entity[(it)] = propClass.newInstance()
                        entity[(it)]['id'] = result[key]
                        break
                    default:
                        entity[(it)] = result[key]
                }
            }
        }
        return entity
    }

    /**
     * where 查询
     */
    static class Where<D> {
        String whereSql
        List params
        Class entityClass

        D get() {
            List list = list([offset: 0, max: 1])
            if (list) return list[0]
            return null
        }

        List<D> list(Map pageParams) {
            return DB.withSql { Sql sql ->
                List list = []
                List rows = sql.rows("select * from ${findTableName(entityClass)} ${whereSql ? 'where ' + whereSql : ''} ${EntityUtil.params(pageParams)}".toString(), params)
                rows.each { row ->
                    list << bindResultToEntity(row, entityClass)
                }
                sql.close()
                return list
            }
        }

        int count() {
            DB.withSql { Sql sql -> sql.firstRow("select count(*) as num from ${findTableName(entityClass)} ${whereSql ? 'where ' + whereSql : ''}".toString(), params).num }
        }
    }

    /**
     * 验证约束
     * @param entity
     * @return
     */
    static boolean validate(Entity entity) {
        if (!entity.hasProperty('id')) throw new Exception("该类没有 id 属性，差评")

        entity.errors = [] //置空
        getConstraintMap(entity.class).each {
            def propertyConstraints = it
            def value = entity[propertyConstraints.key]
            Map constraintsToValidate = propertyConstraints.value

            //comment
            constraintsToValidate.remove('comment')

            //null 处理
            if (null == value) {
                if (!constraintsToValidate.nullable) entity.errors << [propertyConstraints.key, 'nullable']
                return
            }
            constraintsToValidate.remove('nullable')

            //blank
            if ('' == value) {
                if (!constraintsToValidate.blank) entity.errors << [propertyConstraints.key, 'blank']
                return
            }
            constraintsToValidate.remove('blank')

            constraintsToValidate.each {
                if (!Validator.validate(value, it)) {
                    entity.errors << [propertyConstraints.key, it.key]
                }
            }
        }

        entity.errors ? false : true
    }

    /**
     * 获取约束表
     * todo 缓存一下更好
     * @param entityClass
     * @return
     */
    static Map<String, Map> getConstraintMap(Class entityClass) {
        Constraints.buildToMapFromEntityClass(entityClass)
    }

    /**
     * 绑定参数到实体
     * @param entityClass
     * @param params
     * @return
     */
    static bind(Class entityClass, Params params) {
        def entity = entityClass.newInstance()
        bind(entity, params)
        return entity
    }

    static bind(Object entity, Params params) {
        Class entityClass = entity.class
        List props = findPropertiesToPersist(entityClass) - 'id'
        props.each {
            if (params[it]) entity[it] = Transformer.toType(entityClass, it, params[it])
        }
        return entity
    }
}
