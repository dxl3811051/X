package center.web.service;

import center.manager.ClusterManager;
import center.utils.ChromeUtil;
import com.dao.MongoDao;
import com.dao.RedisDao;
import center.dispatch.Dispatcher;
import com.entity.*;
import center.exception.WebException;
import spider.parser.TestBodyParser;
import spider.parser.TestIndexParser;
import com.utils.FieldUtil;
import com.utils.UrlUtil;
import jdk.nashorn.internal.runtime.regexp.joni.exception.ValueException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections.CollectionUtils;
import org.quartz.CronExpression;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import us.codecraft.webmagic.Spider;

import java.util.*;
import java.util.stream.Collectors;

import static center.exception.ErrorCode.*;

@Service
@Slf4j
public class TaskService {

    @Autowired
    private MongoDao mongoDao;

    @Autowired
    private Dispatcher dispatcher;

    @Autowired
    private LogService crawlService;

    @Autowired
    private RedisDao redisDao;

    @Autowired
    private ClusterManager clusterManager;

    @Autowired
    private DocService docService;


    public void addTask(TaskDO task, NewsParserDO parserConfig) {
        //检查重复,验证cron
        //加入mongo
        //暂时不启动
        task.setId(null);
        parserConfig.setId(null);
        task.setActive(false);

        checkTaskInfo(task, true);
        checkParserInfo(parserConfig, true);

        task.setOpDate(new Date());
        mongoDao.saveNewsParser(parserConfig);
        task.setParserId(parserConfig.getId());
        mongoDao.saveTask(task);
        log.info("新建任务成功:{}", task);

        AuditDO audit = new AuditDO("task", "create", "业务需要", task.getId(), new Date());
        mongoDao.saveAudit(audit);
    }


    public void updateTask(TaskDO task, NewsParserDO parserConfig) {
        //更新数据库
        //更新Dispatcher,如果Dispatcher不存在,则跳过,否则重新载入
        checkTaskInfo(task, false);

        checkParserInfo(parserConfig, false);

        //重新覆盖存入
        task.setActive(false);
        task.setOpDate(new Date());
        mongoDao.updateTask(task);
        mongoDao.updateNewsParser(parserConfig);

        //删除Dispatcher的定时任务
        dispatcher.delTask(task);
        log.info("更新任务成功");

        AuditDO audit = new AuditDO("task", "update", "业务需要", task.getId(), new Date());
        mongoDao.saveAudit(audit);
    }

    public TaskDO findTask(String taskId) {
        return mongoDao.findTaskById(taskId);
    }

    public NewsParserDO findNewsParser(String parserId) {
        return mongoDao.findNewsParserById(parserId);
    }


    public void removeTask(String taskId) {
        //删除任务,停止dispatcher,抓取结果暂时不删除
        TaskDO task = existTask(taskId);

        //删除采集任务配置
        mongoDao.delTask(task);
        mongoDao.delNewsParser(task.getParserId());
        dispatcher.delTask(task);

        //删除新闻和指纹
        docService.clearDoc(taskId);


        log.info("删除任务[success]:{}", task);

        AuditDO audit = new AuditDO("task", "del", "业务需要", task.getId(), new Date());
        mongoDao.saveAudit(audit);
    }

    public void stopTask(String taskId) {
        //修改db+dispatcher
        TaskDO task = existTask(taskId);

        dispatcher.delTask(task);
        task.setOpDate(new Date());
        task.setActive(false);
        mongoDao.updateTask(task);

        // TODO: 2020/12/22 考虑用监听者模式重构,后续可能需要添加功能
        AuditDO audit = new AuditDO("task", "stop", "业务需要", task.getId(), new Date());
        mongoDao.saveAudit(audit);
    }

    public void startTask(String taskId) {
        //判定存在
        TaskDO task = existTask(taskId);

        //修改db+添加分配器
        dispatcher.cronTask(task, task.getCron());
        task.setActive(true);
        task.setOpDate(new Date());
        mongoDao.updateTask(task);
//        mongoDao.updateTaskState(task, true);

        AuditDO audit = new AuditDO("task", "start", "业务需要", task.getId(), new Date());
        mongoDao.saveAudit(audit);
    }


    public List<TaskDO> getTasks(Integer pageIndex, Integer pageSize, String keyword) {
        List<TaskDO> all = mongoDao.findTasksByPageIndex(pageIndex, pageSize, keyword);

        //查询任务的上次启动时间
        List<String> taskIds = all.stream().map(TaskDO::getId).collect(Collectors.toList());
        Map<String, CrawlLogDO> lastCrawlInfo = crawlService.getLastCrawlInfo(taskIds);
        all.forEach(task -> {
            if (lastCrawlInfo.get(task.getId()) != null) {
                //防止任务从未启动的情况
                task.setLastRun(lastCrawlInfo.get(task.getId()).getStartTime());
            }
        });

        //查询运行在哪个节点上
        all.forEach(task -> task.setRunHost(clusterManager.getNodeByTaskId(task.getId())));
        return all;
    }

    public List<TaskDO> getTasks2(Integer pageIndex, Integer pageSize) {
        return getTasks(pageIndex, pageSize, null);
    }

    public long getTaskCount() {
        return mongoDao.taskCount();
    }

    private void checkParserInfo(NewsParserDO parser, boolean create) {
        if (!create) existIndexParser(parser.getId());

        //验证extra的格式
        checkExtraNotEmpty(parser.getExtra());

        //根据配置类型不同 分开进行检查
        if (parser instanceof IndexParserDO) {
            IndexParserDO indexParserDO = (IndexParserDO) parser;
            if (!FieldUtil.checkParamNotEmpty(indexParserDO.getIndexRule())) {
                throw new WebException(SERVICE_PARSER_MISS_MUST_FIELD);
            }
        }

        if (parser instanceof PageParserDO) {
            PageParserDO parserDO = (PageParserDO) parser;
            if (!FieldUtil.checkParamNotEmpty(parserDO.getPageRule())) {
                throw new WebException(SERVICE_PARSER_MISS_MUST_FIELD);
            }
        }
    }

    private void checkExtraNotEmpty(List<FieldDO> fields) {
        if (!CollectionUtils.isEmpty(fields)) return;

        //name必须有,css| xpath | re | special  必须有一个
        for (FieldDO f : fields) {
            if (f.getName() == null) throw new WebException(SERVICE_PARSER_MISS_FIELD_NAME);
            if (!FieldUtil.checkParamNotEmpty(f.getCss(), f.getXpath(), f.getSpecial(), f.getRe()))
                throw new WebException(SERVICE_PARSER_MISS_FIELD_VALUE);
        }

        //验证是否有重名
        Set<String> set = fields.stream().map(FieldDO::getName).collect(Collectors.toSet());
        if (fields.size() != set.size()) throw new WebException(SERVICE_PARSER_FIELD_DUP);
    }


    private void checkTaskInfo(TaskDO task, boolean create) {
        //保证task是存在的
        TaskDO old = null;
        if (!create) old = existTask(task.getId());

        //  验证必须的参数是否完整
        if (!FieldUtil.checkParamNotEmpty(
                task.getName(),
                task.getStartUrl(),
                task.getCron(),
                task.getParserType()
        )) {
            throw new WebException(SERVICE_TASK_CREATE_MISS_PARAM);
        }

        //判断更新后的任务是否名字和其他任务有冲突
        String taskUrl = task.getStartUrl();
        List<TaskDO> otherUrl = mongoDao.findTaskByUrl(task.getStartUrl(), task.getName());

        //去掉本身的任务
        if (!create) {
            TaskDO finalOld = old;
            otherUrl = otherUrl.stream().filter(item -> !item.getId().equals(finalOld.getId())).collect(Collectors.toList());
        }

        //验证task的名字和url是否重复
        if (!CollectionUtils.isEmpty(otherUrl)) {
            if (otherUrl.stream().anyMatch(x -> taskUrl.equals(x.getName()))) {
                throw new WebException(SERVICE_TASK_CREATE_NAME_DUP);
            } else throw new WebException(SERVICE_TASK_CREATE_URL_DUP);
        }

        //验证task的cron是否规范
        if (!CronExpression.isValidExpression(task.getCron())) {
            throw new WebException(SERVICE_TASK_CRON_INVALID);
        }

        //验证url是否规范
        if (!UrlUtil.checkUrl(task.getStartUrl())) {
            throw new WebException(SERVICE_TASK_URL_INVALID);
        }
    }


    private TaskDO existTask(String taskId) {
        if (taskId == null) throw new ValueException("taskId is null ! ! ");
        TaskDO taskById = mongoDao.findTaskById(taskId);
        if (taskById == null) throw new WebException(SERVICE_TASK_NOT_EXIST);
        return taskById;
    }

    private NewsParserDO existIndexParser(String parserId) {
        if (parserId == null) throw new ValueException("parserId is null !");
        NewsParserDO parser = mongoDao.findNewsParserById(parserId);
        if (parser == null) throw new WebException(SERVICE_PARSER_NOT_EXIST);
        return parser;
    }

    public List<String> testIndex(TaskDO task, IndexParserDO indexParser) {
        checkParserInfo(indexParser, true);

        List<String> rnt = new ArrayList<>();
        TestIndexParser spider = new TestIndexParser(task, indexParser, rnt);
        //抓取单页面
        Spider app = Spider.create(spider).addUrl(task.getStartUrl()).thread(1);
        if (task.isDynamic()) {
            app.setDownloader(ChromeUtil.chromeDownloader);
        }
        app.run();
        return rnt;
    }

    public Map<String, Object> testBody(TaskDO task, NewsParserDO indexParserBO, String targetUrl) {
        checkParserInfo(indexParserBO, true);

        Map<String, Object> rnt = new HashMap<>();
        TestBodyParser spider = new TestBodyParser(task, indexParserBO, rnt);
        Spider.create(spider).addUrl(targetUrl).thread(1).run();
        return rnt;
    }
}
