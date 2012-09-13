package clj_liquibase;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

import liquibase.change.Change;
import liquibase.changelog.ChangeSet;
import liquibase.changelog.DatabaseChangeLog;
import liquibase.changelog.visitor.ChangeSetVisitor;
import liquibase.database.Database;
import liquibase.database.structure.Column;
import liquibase.database.structure.DatabaseObject;
import liquibase.database.structure.Table;
import liquibase.dbdoc.AuthorListWriter;
import liquibase.dbdoc.AuthorWriter;
import liquibase.dbdoc.ChangeLogListWriter;
import liquibase.dbdoc.ChangeLogWriter;
import liquibase.dbdoc.ColumnWriter;
import liquibase.dbdoc.HTMLWriter;
import liquibase.dbdoc.PendingChangesWriter;
import liquibase.dbdoc.PendingSQLWriter;
import liquibase.dbdoc.RecentChangesWriter;
import liquibase.dbdoc.TableListWriter;
import liquibase.dbdoc.TableWriter;
import liquibase.exception.DatabaseException;
import liquibase.exception.DatabaseHistoryException;
import liquibase.exception.LiquibaseException;
import liquibase.resource.ResourceAccessor;
import liquibase.snapshot.DatabaseSnapshot;
import liquibase.snapshot.DatabaseSnapshotGeneratorFactory;
import liquibase.util.StreamUtil;

public class CustomDBDocVisitor implements ChangeSetVisitor {

    private Database database;

    private SortedSet<ChangeLogInfo> changeLogs;
    private Map<DatabaseObject, List<Change>> changesByObject;
    private Map<String, List<Change>> changesByAuthor;

    private Map<DatabaseObject, List<Change>> changesToRunByObject;
    private Map<String, List<Change>> changesToRunByAuthor;
    private List<Change> changesToRun;
    private List<Change> recentChanges;

    private String rootChangeLogName;
    private DatabaseChangeLog rootChangeLog;

    private static final int MAX_RECENT_CHANGE = 50;

    public CustomDBDocVisitor(Database database) {
        this.database = database;

        changesByObject = new HashMap<DatabaseObject, List<Change>>();
        changesByAuthor = new HashMap<String, List<Change>>();
        changeLogs = new TreeSet<ChangeLogInfo>();

        changesToRunByObject = new HashMap<DatabaseObject, List<Change>>();
        changesToRunByAuthor = new HashMap<String, List<Change>>();
        changesToRun = new ArrayList<Change>();
        recentChanges = new ArrayList<Change>();
    }

    public ChangeSetVisitor.Direction getDirection() {
        return ChangeSetVisitor.Direction.FORWARD;
    }

    public void visit(ChangeSet changeSet, DatabaseChangeLog databaseChangeLog, Database database) throws LiquibaseException {
        ChangeSet.RunStatus runStatus = this.database.getRunStatus(changeSet);
        if (rootChangeLogName == null) {
            rootChangeLogName = changeSet.getFilePath();
        }

        if (rootChangeLog == null) {
            this.rootChangeLog = databaseChangeLog;
        }

        if (!changesByAuthor.containsKey(changeSet.getAuthor())) {
            changesByAuthor.put(changeSet.getAuthor(), new ArrayList<Change>());
        }
        if (!changesToRunByAuthor.containsKey(changeSet.getAuthor())) {
            changesToRunByAuthor.put(changeSet.getAuthor(), new ArrayList<Change>());
        }

        boolean toRun = runStatus.equals(ChangeSet.RunStatus.NOT_RAN) || runStatus.equals(ChangeSet.RunStatus.RUN_AGAIN);
        for (Change change : changeSet.getChanges()) {
            if (toRun) {
                changesToRunByAuthor.get(changeSet.getAuthor()).add(change);
                changesToRun.add(change);
            } else {
                changesByAuthor.get(changeSet.getAuthor()).add(change);
                recentChanges.add(0, change);
            }
        }


        ChangeLogInfo changeLogInfo = new ChangeLogInfo(changeSet.getFilePath(), databaseChangeLog.getPhysicalFilePath());
        if (!changeLogs.contains(changeLogInfo)) {
            changeLogs.add(changeLogInfo);
        }

        for (Change change : changeSet.getChanges()) {
            Set<DatabaseObject> affectedDatabaseObjects = change.getAffectedDatabaseObjects(database);
            if (affectedDatabaseObjects != null) {
                for (DatabaseObject dbObject : affectedDatabaseObjects) {
                    if (toRun) {
                        if (!changesToRunByObject.containsKey(dbObject)) {
                            changesToRunByObject.put(dbObject, new ArrayList<Change>());
                        }
                        changesToRunByObject.get(dbObject).add(change);
                    }

                    if (!changesByObject.containsKey(dbObject)) {
                        changesByObject.put(dbObject, new ArrayList<Change>());
                    }
                    changesByObject.get(dbObject).add(change);
                }
            }
        }
    }

    public void writeHTML(File rootOutputDir, ResourceAccessor resourceAccessor) throws IOException, DatabaseException, DatabaseHistoryException {
        //ChangeLogWriter changeLogWriter = new ChangeLogWriter(resourceAccessor, rootOutputDir);
        HTMLWriter authorWriter = new AuthorWriter(rootOutputDir, database);
        HTMLWriter tableWriter = new TableWriter(rootOutputDir, database);
        HTMLWriter columnWriter = new ColumnWriter(rootOutputDir, database);
        HTMLWriter pendingChangesWriter = new PendingChangesWriter(rootOutputDir, database);
        HTMLWriter recentChangesWriter = new RecentChangesWriter(rootOutputDir, database);
        HTMLWriter pendingSQLWriter = new PendingSQLWriter(rootOutputDir, database, rootChangeLog);

        copyFile("liquibase/dbdoc/stylesheet.css", rootOutputDir);
        copyFile("liquibase/dbdoc/index.html", rootOutputDir);
        copyFile("liquibase/dbdoc/globalnav.html", rootOutputDir);
        copyFile("liquibase/dbdoc/overview-summary.html", rootOutputDir);

        DatabaseSnapshot snapshot = DatabaseSnapshotGeneratorFactory.getInstance().createSnapshot(database, null, null);

        new ChangeLogListWriter(rootOutputDir).writeHTML(changeLogs);
        new TableListWriter(rootOutputDir).writeHTML(new TreeSet<Object>(snapshot.getTables()));
        new AuthorListWriter(rootOutputDir).writeHTML(new TreeSet<Object>(changesByAuthor.keySet()));

        for (String author : changesByAuthor.keySet()) {
            authorWriter.writeHTML(author, changesByAuthor.get(author), changesToRunByAuthor.get(author), rootChangeLogName);
        }

        for (Table table : snapshot.getTables()) {
            tableWriter.writeHTML(table, changesByObject.get(table), changesToRunByObject.get(table), rootChangeLogName);
        }

        for (Column column : snapshot.getColumns()) {
            columnWriter.writeHTML(column, changesByObject.get(column), changesToRunByObject.get(column), rootChangeLogName);
        }

//        for (ChangeLogInfo changeLog : changeLogs) {
//            changeLogWriter.writeChangeLog(changeLog.logicalPath, changeLog.physicalPath);
//        }

        pendingChangesWriter.writeHTML("index", null, changesToRun, rootChangeLogName);
        pendingSQLWriter.writeHTML("sql", null, changesToRun, rootChangeLogName);

        if (recentChanges.size() > MAX_RECENT_CHANGE) {
            recentChanges = recentChanges.subList(0, MAX_RECENT_CHANGE);
        }
        recentChangesWriter.writeHTML("index", recentChanges, null, rootChangeLogName);

    }

    private void copyFile(String fileToCopy, File rootOutputDir) throws IOException {
        InputStream inputStream = getClass().getClassLoader().getResourceAsStream(fileToCopy);
        FileOutputStream outputStream = null;
        try {
            if (inputStream == null) {
                throw new IOException("Can not find " + fileToCopy);
            }
            outputStream = new FileOutputStream(new File(rootOutputDir, fileToCopy.replaceFirst(".*\\/", "")), false);
            StreamUtil.copy(inputStream, outputStream);
        } finally {
            if (outputStream != null) {
                outputStream.close();
            }
        }
    }

    private static class ChangeLogInfo implements Comparable<ChangeLogInfo> {
        public String logicalPath;
        public String physicalPath;


        private ChangeLogInfo(String logicalPath, String physicalPath) {
            this.logicalPath = logicalPath;
            this.physicalPath = physicalPath;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            ChangeLogInfo that = (ChangeLogInfo) o;

            return logicalPath.equals(that.logicalPath);

        }

        @Override
        public int hashCode() {
            return logicalPath.hashCode();
        }

        public int compareTo(ChangeLogInfo o) {
            return this.logicalPath.compareTo(o.logicalPath);
        }

        @Override
        public String toString() {
            return logicalPath;
        }
    }
}