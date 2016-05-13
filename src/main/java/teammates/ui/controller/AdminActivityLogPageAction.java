package teammates.ui.controller;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.LinkedList;
import java.util.List;
import java.util.TimeZone;

import teammates.common.datatransfer.CourseAttributes;
import teammates.common.datatransfer.FeedbackSessionAttributes;
import teammates.common.exception.EntityDoesNotExistException;
import teammates.common.util.ActivityLogEntry;
import teammates.common.util.AdminLogQuery;
import teammates.common.util.Const;
import teammates.common.util.GaeVersionApi;
import teammates.common.util.GaeLogApi;
import teammates.common.util.StatusMessage;
import teammates.common.util.TimeHelper;
import teammates.common.util.Const.StatusMessageColor;
import teammates.common.util.Version;
import teammates.logic.api.GateKeeper;
import teammates.logic.api.Logic;

import com.google.appengine.api.log.AppLogLine;

public class AdminActivityLogPageAction extends Action {
    private static final int RELEVANT_LOGS_PER_PAGE = 50;
    /**
     * The maximum time period to retrieve logs with time increment.
     */
    private static final int MAX_SEARCH_PERIOD = 24 * 60 * 60 * 1000; // 24 hrs in milliseconds
    private static final int SEARCH_TIME_INCREMENT = 2 * 60 * 60 * 1000;  // two hours in milliseconds
    /*
     * The maximum number of times to retrieve logs with time increment.
     */
    private static final int MAX_SEARCH_TIMES = MAX_SEARCH_PERIOD / SEARCH_TIME_INCREMENT;
    /**
     * Maximum number of versions to query.
     */
    private static final int MAX_VERSIONS_TO_QUERY = 1 + 5; //the current version and its 5 preceding versions
    
    
    private int totalLogsSearched;
    private boolean isFirstRow = true;
    private Long nextEndTimeToSearch;
    
    @Override
    protected ActionResult execute() throws EntityDoesNotExistException{
        new GateKeeper().verifyAdminPrivileges(account);
        
        AdminActivityLogPageData data = new AdminActivityLogPageData(account);
        
        String searchTimeOffset = getRequestParamValue("searchTimeOffset");
        if (searchTimeOffset == null) {
            searchTimeOffset = "";
        }
        String filterQuery = getRequestParamValue("filterQuery");
        String courseIdFromSearchPage = getRequestParamValue("courseId");
        
        String logRoleFromAjax = getRequestParamValue("logRole");
        String logGoogleIdFromAjax = getRequestParamValue("logGoogleId");
        String logTimeInAdminTimeZoneFromAjax = getRequestParamValue("logTimeInAdminTimeZone");
        
        boolean isLoadingLocalTimeAjax = logRoleFromAjax != null
                                         && logGoogleIdFromAjax != null
                                         && logTimeInAdminTimeZoneFromAjax != null;
        
        if (isLoadingLocalTimeAjax) {
            data.setLogLocalTime(getLocalTimeInfo(logGoogleIdFromAjax, 
                                                  logRoleFromAjax,
                                                  logTimeInAdminTimeZoneFromAjax));
            return createAjaxResult(data);
        }
        
//      This parameter determines whether the logs with requests contained in "excludedLogRequestURIs" in AdminActivityLogPageData
//      should be shown. Use "?all=true" in URL to show all logs. This will keep showing all
//      logs despite any action or change in the page unless the the page is reloaded with "?all=false" 
//      or simply reloaded with this parameter omitted.
        boolean ifShowAll = getRequestParamAsBoolean("all");
        
        
//      This determines whether the logs related to testing data should be shown. Use "testdata=true" in URL
//      to show all testing logs. This will keep showing all logs from testing data despite any action or change in the page
//      unless the the page is reloaded with "?testdata=false"  or simply reloaded with this parameter omitted.       
        boolean ifShowTestData = getRequestParamAsBoolean("testdata");
        
        if (filterQuery == null) {
            filterQuery = "";
        }
        //This is used to parse the filterQuery. If the query is not parsed, the filter function would ignore the query
        data.generateQueryParameters(filterQuery);
        
        boolean isContinueFromPreviousSearch = !data.isFromDateSpecifiedInQuery() && !searchTimeOffset.isEmpty();
        if (isContinueFromPreviousSearch) {
            data.setToDate(Long.parseLong(searchTimeOffset));
        }
        
        List<String> versionToQuery = getVersionsForQuery(data.getVersions());
        AdminLogQuery query = new AdminLogQuery(versionToQuery, data.getFromDate(), data.getToDate());
        
        List<ActivityLogEntry> logs = null;
        if (data.isFromDateSpecifiedInQuery()) {
            logs = searchLogsWithExactTimePeriod(query, data);
        } else {
            logs = searchLogsWithTimeIncrement(query, data);
        }
        
        generateStatusMessage(versionToQuery, data, logs, courseIdFromSearchPage);
        data.init(ifShowAll, ifShowTestData, logs);
        
        if (searchTimeOffset.isEmpty()) {
            return createShowPageResult(Const.ViewURIs.ADMIN_ACTIVITY_LOG, data);
        }
        
        return createAjaxResult(data);
    }
    
    /**
     * Selects versions for query. If versions are not specified, it will return 
     * MAX_VERSIONS_TO_QUERY most recent versions used for query.
     */
    private List<String> getVersionsForQuery(List<String> versions) {
        boolean isVersionSpecifiedInRequest = versions != null && !versions.isEmpty();
        if (isVersionSpecifiedInRequest) {
            return versions;
        }
        GaeVersionApi versionApi = new GaeVersionApi();
        return versionApi.getMostRecentVersions(MAX_VERSIONS_TO_QUERY);
    }
    
    private void generateStatusMessage(List<String> versionToQuery, 
                                       AdminActivityLogPageData data, 
                                       List<ActivityLogEntry> logs, String courseId) {
        String status = "Total Logs gone through in last search: " + totalLogsSearched + "<br>";
        status += "Total Relevant Logs found in last search: " + logs.size() + "<br>";
        
        long earliestSearchTime = data.getFromDate();
        ActivityLogEntry earliestLogChecked = null;
        if (!logs.isEmpty()) {
            earliestLogChecked = logs.get(logs.size() - 1);
        }
        //  if the search space is limited to a certain log
        if (logs.size() >= RELEVANT_LOGS_PER_PAGE && earliestLogChecked != null) {
            earliestSearchTime = earliestLogChecked.getTime();
        }
        
        double targetTimeZone = Const.DOUBLE_UNINITIALIZED;
        if (data.isPersonSpecified()) {
            String targetUserGoogleId = data.getPersonSpecified();
            targetTimeZone = getLocalTimeZoneForRequest(targetUserGoogleId, "");

            if (targetTimeZone == Const.DOUBLE_UNINITIALIZED) {
                // if the user is unregistered, try finding the timezone by course id passed from Search page
                if (courseId != null && !courseId.isEmpty()) {
                    targetTimeZone = getLocalTimeZoneForUnregisteredUserRequest(courseId);
                }
            }
        } else {
            targetTimeZone = Const.SystemParams.ADMIN_TIMZE_ZONE_DOUBLE;
        }
        
        double adminTimeZone = Const.SystemParams.ADMIN_TIMZE_ZONE_DOUBLE;
        String timeInAdminTimeZone = computeLocalTime(adminTimeZone, String.valueOf(earliestSearchTime));
        String timeInUserTimeZone =  computeLocalTime(targetTimeZone, String.valueOf(earliestSearchTime));
        status += "The earliest log entry checked on <b>" + timeInAdminTimeZone + "</b> in Admin Time Zone (" 
                  + adminTimeZone + ") and ";
        if (targetTimeZone != Const.DOUBLE_UNINITIALIZED) {
            status += "on <b>" + timeInUserTimeZone + "</b> in Local Time Zone (" + targetTimeZone + ").<br>";
        } else {
            status += timeInUserTimeZone + ".<br>";
        }
        
        status += "Logs are from following version(s): ";
        for (int i = 0; i < versionToQuery.size(); i++) {
            String version = versionToQuery.get(i).replace('-', '.');
            if (i < versionToQuery.size() - 1) {
                status += version + ", ";
            } else {
                status += version + "<br>";
            }
        }
        
        status += "All available version(s): ";
        GaeVersionApi versionApi = new GaeVersionApi();
        List<Version> versionList = versionApi.getAvailableVersions();
        for (int i = 0; i < versionList.size(); i++) {
            String version = versionList.get(i).toString();
            if (i < versionList.size() - 1) {
                status += version + ", ";
            } else {
                status += version + "<br>";
            }
        }
        
        // the "Search More" button to continue searching from the previous fromDate 
        status += "<button class=\"btn-link\" id=\"button_older\" onclick=\"submitFormAjax(" + nextEndTimeToSearch + ");\">Search More</button>";
        
        status += "<input id=\"ifShowAll\" type=\"hidden\" value=\"" + data.getIfShowAll() + "\"/>";
        status += "<input id=\"ifShowTestData\" type=\"hidden\" value=\"" + data.getIfShowTestData() + "\"/>";
        
        data.setStatusForAjax(status);
        statusToUser.add(new StatusMessage(status, StatusMessageColor.INFO));
    }

    /**
     * Retrieves enough logs within MAX_SEARCH_PERIOD hours.
     */
    private List<ActivityLogEntry> searchLogsWithTimeIncrement(AdminLogQuery query, AdminActivityLogPageData data) {
        List<ActivityLogEntry> appLogs = new LinkedList<ActivityLogEntry>();
        
        totalLogsSearched = 0;
        GaeLogApi logApi = new GaeLogApi();
        
        long startTime = query.getEndTime() - SEARCH_TIME_INCREMENT;
        query.setTimePeriod(startTime, query.getEndTime());
        
        for (int i = 0; i < MAX_SEARCH_TIMES; i++) {
            if (appLogs.size() >= RELEVANT_LOGS_PER_PAGE) {
                break;
            }
            List<AppLogLine> searchResult = logApi.fetchLogs(query);
            List<ActivityLogEntry> filteredLogs = filterLogsForActivityLogPage(searchResult, data);
            appLogs.addAll(filteredLogs);
            totalLogsSearched += searchResult.size();
            query.moveTimePeriodBackward(SEARCH_TIME_INCREMENT);
        }
        nextEndTimeToSearch = query.getEndTime();
        return appLogs;
    }
    
    /**
     * Retrieves all logs in the time period specified in the query.
     */
    private List<ActivityLogEntry> searchLogsWithExactTimePeriod(AdminLogQuery query, AdminActivityLogPageData data) {
        GaeLogApi logApi = new GaeLogApi();
        List<AppLogLine> searchResult = logApi.fetchLogs(query);
        List<ActivityLogEntry> filteredLogs = filterLogsForActivityLogPage(searchResult, data);
        
        nextEndTimeToSearch = data.getFromDate() - 1;
        totalLogsSearched = searchResult.size();
        return filteredLogs;
    }
    
    /**
     * Filters logs that should be shown on Admin Activity Log Page.
     */
    private List<ActivityLogEntry> filterLogsForActivityLogPage(List<AppLogLine> appLogLines,
                                                                AdminActivityLogPageData data) {
        List<ActivityLogEntry> appLogs = new LinkedList<ActivityLogEntry>();
        for (AppLogLine appLog : appLogLines) {
            String logMsg = appLog.getLogMessage();
            boolean isNotTeammatesLog = !logMsg.contains("TEAMMATESLOG");
            boolean isLogFromAdminActivityLogPage = logMsg.contains("adminActivityLogPage");
            if (isNotTeammatesLog || isLogFromAdminActivityLogPage) {
                continue;
            }
            
            ActivityLogEntry activityLogEntry = new ActivityLogEntry(appLog);
            activityLogEntry = data.filterLogs(activityLogEntry);
            
            boolean isToShow = activityLogEntry.toShow() && (!activityLogEntry.isTestingData() || data.getIfShowTestData());
            if (!isToShow) {
                continue;
            }
            if (isFirstRow) {
                activityLogEntry.setFirstRow();
                isFirstRow = false;
            }
            appLogs.add(activityLogEntry);
        }
        return appLogs;
    }

    private double getLocalTimeZoneForRequest(String userGoogleId, String userRole) {
        double localTimeZone = Const.DOUBLE_UNINITIALIZED;
        
        if (userRole != null && (userRole.contentEquals("Admin") || userRole.contains("(M)"))) {
            return Const.SystemParams.ADMIN_TIMZE_ZONE_DOUBLE;
        }
        
        Logic logic = new Logic();
        if (userGoogleId != null && !userGoogleId.isEmpty()) {
            try {
                localTimeZone = findAvailableTimeZoneFromCourses(logic.getCoursesForInstructor(userGoogleId));
            } catch (EntityDoesNotExistException e) {
                localTimeZone = Const.DOUBLE_UNINITIALIZED;
            }
            
            if (localTimeZone != Const.DOUBLE_UNINITIALIZED) {
                return localTimeZone;
            }
             
            try {
                localTimeZone = findAvailableTimeZoneFromCourses(logic.getCoursesForStudentAccount(userGoogleId));
            } catch (EntityDoesNotExistException e) {
                localTimeZone = Const.DOUBLE_UNINITIALIZED;
            }
            
            if (localTimeZone != Const.DOUBLE_UNINITIALIZED) {
                return localTimeZone;
            }
        }
        
        return localTimeZone;
    }
    
    private double findAvailableTimeZoneFromCourses(List<CourseAttributes> courses) {
        double localTimeZone = Const.DOUBLE_UNINITIALIZED;
        
        if (courses == null) {
            return localTimeZone;
        }
        
        Logic logic = new Logic();
        
        for (CourseAttributes course : courses) {
            List<FeedbackSessionAttributes> fsl = logic.getFeedbackSessionsForCourse(course.getId()); 
            if (fsl != null && !fsl.isEmpty()) {
                return fsl.get(0).timeZone;
            }
        }
        
        return localTimeZone;
    }
    
    private double getLocalTimeZoneForUnregisteredUserRequest(String courseId) {
        double localTimeZone = Const.DOUBLE_UNINITIALIZED;
        
        if (courseId == null || courseId.isEmpty()) {
            return localTimeZone;
        }
        
        Logic logic = new Logic();
        
        List<FeedbackSessionAttributes> fsl = logic.getFeedbackSessionsForCourse(courseId); 
        if (fsl != null && !fsl.isEmpty()) {
            return fsl.get(0).timeZone;
        }
        
        return localTimeZone;
    }
    
    private double getLocalTimeZoneInfo(String logGoogleId, String logRole) {
        if (!logGoogleId.contentEquals("Unknown") && !logGoogleId.contentEquals("Unregistered")) {
            return getLocalTimeZoneForRequest(logGoogleId, logRole);
        } else if (logRole.contains("Unregistered") && !logRole.contentEquals("Unregistered")) {
            String coureseId = logRole.split(":")[1];
            return getLocalTimeZoneForUnregisteredUserRequest(coureseId);
        } else {
            return Const.DOUBLE_UNINITIALIZED;
        }
    }
    
    private String getLocalTimeInfo(String logGoogleId, String logRole, String logTimeInAdminTimeZone) {
        double timeZone = getLocalTimeZoneInfo(logGoogleId, logRole);
        if (timeZone != Const.DOUBLE_UNINITIALIZED) {
            return computeLocalTime(timeZone, logTimeInAdminTimeZone);
        } else {
            return "Local Time Unavailable";
        }
    }
    
    private String computeLocalTime(double timeZone, String logTimeInAdminTimeZone) {
        if (timeZone == Const.DOUBLE_UNINITIALIZED) {
            return "Local Time Unavailable";
        }
        
        Calendar appCal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        SimpleDateFormat sdf = new SimpleDateFormat("dd-MM-yyyy HH:mm:ss");
        appCal.setTimeInMillis(Long.parseLong(logTimeInAdminTimeZone));
        TimeHelper.convertToUserTimeZone(appCal, timeZone);
        return sdf.format(appCal.getTime());
    }
}
