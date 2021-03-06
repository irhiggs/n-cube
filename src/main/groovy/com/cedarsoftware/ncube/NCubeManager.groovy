package com.cedarsoftware.ncube

import com.cedarsoftware.ncube.exception.BranchMergeException
import com.cedarsoftware.util.ArrayUtilities
import com.cedarsoftware.util.CaseInsensitiveMap
import com.cedarsoftware.util.CaseInsensitiveSet
import com.cedarsoftware.util.Converter
import com.cedarsoftware.util.IOUtilities
import com.cedarsoftware.util.MapUtilities
import com.cedarsoftware.util.StringUtilities
import com.cedarsoftware.util.SystemUtilities
import com.cedarsoftware.util.TrackingMap
import com.cedarsoftware.util.io.JsonObject
import com.cedarsoftware.util.io.JsonReader
import com.cedarsoftware.util.io.JsonWriter
import groovy.transform.CompileStatic
import ncube.grv.method.NCubeGroovyController
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap
import java.util.regex.Pattern

/**
 * This class manages a list of NCubes.  This class is referenced
 * by NCube in one place - when it joins to other cubes, it consults
 * the NCubeManager to find the joined NCube.
 * <p/>
 * This class takes care of creating, loading, updating, releasing,
 * and deleting NCubes.  It also allows you to get a list of NCubes
 * matching a wildcard (SQL Like) string.
 *
 * @author John DeRegnaucourt (jdereg@gmail.com)
 *         <br>
 *         Copyright (c) Cedar Software LLC
 *         <br><br>
 *         Licensed under the Apache License, Version 2.0 (the "License")
 *         you may not use this file except in compliance with the License.
 *         You may obtain a copy of the License at
 *         <br><br>
 *         http://www.apache.org/licenses/LICENSE-2.0
 *         <br><br>
 *         Unless required by applicable law or agreed to in writing, software
 *         distributed under the License is distributed on an "AS IS" BASIS,
 *         WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either eƒfetxpress or implied.
 *         See the License for the specific language governing permissions and
 *         limitations under the License.
 */
@CompileStatic
class NCubeManager
{
    public static final String ERROR_CANNOT_MOVE_000 = 'Version 0.0.0 is for system configuration and cannot be move.'
    public static final String ERROR_CANNOT_MOVE_TO_000 = 'Version 0.0.0 is for system configuration and branch cannot be moved to it.'
    public static final String ERROR_CANNOT_RELEASE_000 = 'Version 0.0.0 is for system configuration and cannot be released.'
    public static final String ERROR_CANNOT_RELEASE_TO_000 = 'Version 0.0.0 is for system configuration and cannot be created from the release process.'
    public static final String ERROR_NOT_ADMIN = 'Operation not performed. You do not have admin permissions for '

    public static final String SEARCH_INCLUDE_CUBE_DATA = 'includeCubeData'
    public static final String SEARCH_INCLUDE_TEST_DATA = 'includeTestData'
    public static final String SEARCH_INCLUDE_NOTES = 'includeNotes'
    public static final String SEARCH_DELETED_RECORDS_ONLY = 'deletedRecordsOnly'
    public static final String SEARCH_ACTIVE_RECORDS_ONLY = 'activeRecordsOnly'
    public static final String SEARCH_CHANGED_RECORDS_ONLY = 'changedRecordsOnly'
    public static final String SEARCH_EXACT_MATCH_NAME = 'exactMatchName'

    public static final String BRANCH_UPDATES = 'updates'
    public static final String BRANCH_MERGES = 'merges'
    public static final String BRANCH_CONFLICTS = 'conflicts'

    public static final String SYS_BOOTSTRAP = 'sys.bootstrap'
    public static final String SYS_PROTOTYPE = 'sys.prototype'
    public static final String SYS_PERMISSIONS = 'sys.permissions'
    public static final String SYS_USERGROUPS = 'sys.usergroups'
    public static final String SYS_LOCK = 'sys.lock'
    public static final String SYS_BRANCH_PERMISSIONS = 'sys.branch.permissions'
    public static final String CLASSPATH_CUBE = 'sys.classpath'

    public static final String ROLE_ADMIN = 'admin'
    public static final String ROLE_USER = 'user'
    public static final String ROLE_READONLY = 'readonly'

    public static final String AXIS_ROLE = 'role'
    public static final String AXIS_USER = 'user'
    public static final String AXIS_RESOURCE = 'resource'
    public static final String AXIS_ACTION = 'action'
    public static final String AXIS_SYSTEM = 'system'

    public static final String PROPERTY_CACHE = 'cache'

    // Maintain cache of 'wildcard' patterns to Compiled Pattern instance
    private static ConcurrentMap<String, Pattern> wildcards = new ConcurrentHashMap<>()
    private static final ConcurrentMap<ApplicationID, ConcurrentMap<String, Object>> ncubeCache = new ConcurrentHashMap<>()
    private static final ConcurrentMap<ApplicationID, ConcurrentMap<String, Advice>> advices = new ConcurrentHashMap<>()
    private static final ConcurrentMap<ApplicationID, GroovyClassLoader> localClassLoaders = new ConcurrentHashMap<>()
    static final String NCUBE_PARAMS = 'NCUBE_PARAMS'
    private static NCubePersister nCubePersister
    private static final Logger LOG = LogManager.getLogger(NCubeManager.class)

    // not private in case we want to tweak things for testing.
    protected static volatile ConcurrentMap<String, Object> systemParams = null

    private static final ThreadLocal<String> userId = new ThreadLocal<String>() {
        public String initialValue()
        {
            Map params = getSystemParams()
            String userId = params.user instanceof String ? params.user : System.getProperty('user.name')
            return userId?.trim()
        }
    }

    static enum ACTION {
        COMMIT,
        READ,
        RELEASE,
        UPDATE

        String lower()
        {
            return name().toLowerCase()
        }
    }

    private static final List CUBE_MUTATE_ACTIONS = [ACTION.COMMIT, ACTION.UPDATE]

    /**
     * Store the Persister to be used with the NCubeManager API (Dependency Injection API)
     */
    static void setNCubePersister(NCubePersister persister)
    {
        nCubePersister = persister
    }

    static NCubePersister getPersister()
    {
        if (nCubePersister == null)
        {
            throw new IllegalStateException('Persister not set into NCubeManager.')
        }
        return nCubePersister
    }

    static Map<String, Object> getSystemParams()
    {
        final ConcurrentMap<String, Object> params = systemParams

        if (params != null)
        {
            return params
        }

        synchronized (NCubeManager.class)
        {
            if (systemParams == null)
            {
                String jsonParams = SystemUtilities.getExternalVariable(NCUBE_PARAMS)
                ConcurrentMap sysParamMap = new ConcurrentHashMap<>()

                if (StringUtilities.hasContent(jsonParams))
                {
                    try
                    {
                        sysParamMap = new ConcurrentHashMap<>((Map) JsonReader.jsonToJava(jsonParams, [(JsonReader.USE_MAPS): true] as Map))
                    }
                    catch (Exception ignored)
                    {
                        LOG.warn('Parsing of NCUBE_PARAMS failed. ' + jsonParams)
                    }
                }
                systemParams = sysParamMap
            }
        }
        return systemParams
    }

    /**
     * Fetch all the n-cube names for the given ApplicationID.  This API
     * will load all cube records for the ApplicationID (NCubeInfoDtos),
     * and then get the names from them.
     *
     * @return Set < String >  n-cube names.  If an empty Set is returned,
     * then there are no persisted n-cubes for the passed in ApplicationID.
     */
    @Deprecated
    protected static Set<String> getCubeNames(ApplicationID appId)
    {
        List<NCubeInfoDto> cubeInfos = search(appId, null, null, [(SEARCH_ACTIVE_RECORDS_ONLY): true])
        Set<String> names = new TreeSet<>()

        for (NCubeInfoDto info : cubeInfos)
        {   // Permission check happened in search()
            names.add(info.name)
        }

        if (names.isEmpty())
        {   // Support tests that load cubes from JSON files...
            // can only be in there as ncubes, not ncubeDtoInfo
            for (Object value : getCacheForApp(appId).values())
            {
                if (value instanceof NCube)
                {
                    NCube cube = (NCube) value
                    names.add(cube.getName())
                }
            }
        }
        return new CaseInsensitiveSet<>(names)
    }

    /**
     * Load n-cube, bypassing any caching.  This is necessary for n-cube-editor (IDE time
     * usage).  If the IDE environment is clustered, cannot be getting stale copies from
     * cache.  Any advices in the manager will be applied to the n-cube.
     * @return NCube of the specified name from the specified AppID, or null if not found.
     */
    static NCube loadCube(ApplicationID appId, String cubeName)
    {
        assertPermissions(appId, cubeName)
        NCube ncube = getPersister().loadCube(appId, cubeName)
        if (ncube == null)
        {
            return null
        }
        applyAdvices(ncube.getApplicationID(), ncube)
        cacheCube(appId, ncube)
        return ncube
    }

    /**
     * Fetch an n-cube by name from the given ApplicationID.  If no n-cubes
     * are loaded, then a loadCubes() call is performed and then the
     * internal cache is checked again.  If the cube is not found, null is
     * returned.
     */
    static NCube getCube(ApplicationID appId, String cubeName)
    {
        validateAppId(appId)
        assertPermissions(appId, cubeName)
        NCube.validateCubeName(cubeName)
        return getCubeInternal(appId, cubeName)
    }

    private static NCube getCubeInternal(ApplicationID appId, String cubeName)
    {
        Map<String, Object> cubes = getCacheForApp(appId)
        final String lowerCubeName = cubeName.toLowerCase()

        if (cubes.containsKey(lowerCubeName))
        {   // pull from cache
            final Object cube = cubes[lowerCubeName]
            return Boolean.FALSE == cube ? null : cube as NCube
        }

        // now even items with metaProperties(cache = 'false') can be retrieved
        // and normal app processing doesn't do two queries anymore.
        // used to do getCubeInfoRecords() -> dto
        // and then dto -> loadCube(id)
        NCube ncube = getPersister().loadCube(appId, cubeName)
        if (ncube == null)
        {   // Associate 'failed to load' with Boolean.FALSE so no further attempts are made to load it
            cubes[lowerCubeName] = Boolean.FALSE
            return null
        }
        return prepareCube(ncube)
    }

    private static NCube prepareCube(NCube cube)
    {
        applyAdvices(cube.getApplicationID(), cube)
        cacheCube(cube.getApplicationID(), cube)
        return cube
    }

    /**
     * Load the n-cube with the specified id.  This is useful in n-cube editors, where a user wants to pick
     * an older revision and load / compare it.
     * @param id long n-cube id.
     * @return NCube that has the passed in id.
     */
    static NCube loadCubeById(long id)
    {
        NCube ncube = getPersister().loadCubeById(id)
        return prepareCube(ncube)
    }

    /**
     * Fetch the classloader for the given ApplicationID.
     */
    protected static URLClassLoader getUrlClassLoader(ApplicationID appId, Map input)
    {
        NCube cpCube = getCube(appId, CLASSPATH_CUBE)

        if (cpCube == null)
        {   // No sys.classpath cube exists, just create regular GroovyClassLoader with no URLs set into it.
            // Scope the GroovyClassLoader per ApplicationID
            return getLocalClassloader(appId)
        }

        final String envLevel = SystemUtilities.getExternalVariable('ENV_LEVEL')
        if (StringUtilities.hasContent(envLevel) && !doesMapContainKey(input, 'env'))
        {   // Add in the 'ENV_LEVEL" environment variable when looking up sys.* cubes,
            // if there was not already an entry for it.
            input.env = envLevel
        }
        if (!doesMapContainKey(input, 'username'))
        {   // same as ENV_LEVEL, add it in if not already there.
            input.username = System.getProperty('user.name')
        }
        Object urlCpLoader = cpCube.getCell(input)

        if (urlCpLoader instanceof URLClassLoader)
        {
            return (URLClassLoader)urlCpLoader
        }

        throw new IllegalStateException('If the sys.classpath cube exists, it must return a URLClassLoader.')
    }

    private static boolean doesMapContainKey(Map map, String key)
    {
        if (map instanceof TrackingMap)
        {
            Map wrappedMap = ((TrackingMap)map).getWrappedMap()
            return wrappedMap.containsKey(key)
        }
        return map.containsKey(key)
    }

    protected static URLClassLoader getLocalClassloader(ApplicationID appId)
    {
        GroovyClassLoader gcl = localClassLoaders[appId]
        if (gcl == null)
        {
            gcl = new GroovyClassLoader()
            GroovyClassLoader classLoaderRef = localClassLoaders.putIfAbsent(appId, gcl)
            if (classLoaderRef != null)
            {
                gcl = classLoaderRef
            }
        }
        return gcl
    }

    /**
     * Add a cube to the internal cache of available cubes.
     * @param ncube NCube to add to the list.
     */
    static void addCube(ApplicationID appId, NCube ncube)
    {
        validateAppId(appId)
        validateCube(ncube)

        // Apply any matching advices to it
        applyAdvices(appId, ncube)
        cacheCube(appId, ncube)
    }

    /**
     * Fetch the Map of n-cubes for the given ApplicationID.  If no
     * cache yet exists, a new empty cache is added.
     */
    protected static Map<String, Object> getCacheForApp(ApplicationID appId)
    {
        ConcurrentMap<String, Object> ncubes = ncubeCache[appId]

        if (ncubes == null)
        {
            ncubes = new ConcurrentHashMap<>()
            ConcurrentMap<String, Object> mapRef = ncubeCache.putIfAbsent(appId, ncubes)
            if (mapRef != null)
            {
                ncubes = mapRef
            }
        }
        return ncubes
    }

    static void clearCacheForBranches(ApplicationID appId)
    {
        synchronized (ncubeCache)
        {
            Set<ApplicationID> set = [] as Set

            for (ApplicationID id : ncubeCache.keySet())
            {
                if (id.cacheKey().startsWith(appId.branchAgnosticCacheKey()))
                {
                    set.add(id)
                }
            }

            for (ApplicationID appId1 : set)
            {
                clearCache(appId1)
            }
        }
    }

    /**
     * Clear the cube (and other internal caches) for a given ApplicationID.
     * This will remove all the n-cubes from memory, compiled Groovy code,
     * caches related to expressions, caches related to method support,
     * advice caches, and local classes loaders (used when no sys.classpath is
     * present).
     *
     * @param appId ApplicationID for which the cache is to be cleared.
     */
    static void clearCache(ApplicationID appId)
    {
        synchronized (ncubeCache)
        {
            validateAppId(appId)

            Map<String, Object> appCache = getCacheForApp(appId)
            clearGroovyClassLoaderCache(appCache)

            appCache.clear()
            GroovyBase.clearCache(appId)
            NCubeGroovyController.clearCache(appId)

            // Clear Advice cache
            Map<String, Advice> adviceCache = advices[appId]
            if (adviceCache != null)
            {
                adviceCache.clear()
            }

            // Clear ClassLoader cache
            GroovyClassLoader classLoader = localClassLoaders[appId]
            if (classLoader != null)
            {
                classLoader.clearCache()
                localClassLoaders.remove(appId)
            }
        }
    }

    /**
     * This method will clear all caches for all ApplicationIDs.
     * Do not call it for anything other than test purposes.
     */
    static void clearCache()
    {
        synchronized (ncubeCache)
        {
            List<ApplicationID> list = []

            for (ApplicationID appId : ncubeCache.keySet())
            {
                list.add(appId)
            }

            for (ApplicationID appId1 : list)
            {
                clearCache(appId1)
            }
        }
    }

    private static void clearGroovyClassLoaderCache(Map<String, Object> appCache)
    {
        Object cube = appCache[CLASSPATH_CUBE]
        if (cube instanceof NCube)
        {
            NCube cpCube = cube as NCube
            for (Object content : cpCube.getCellMap().values())
            {
                if (content instanceof UrlCommandCell)
                {
                    ((UrlCommandCell)content).clearClassLoaderCache()
                }
            }
        }
    }

    /**
     * Associate Advice to all n-cubes that match the passed in regular expression.
     */
    static void addAdvice(ApplicationID appId, String wildcard, Advice advice)
    {
        validateAppId(appId)
        ConcurrentMap<String, Advice> current = advices[appId]
        if (current == null)
        {
            current = new ConcurrentHashMap<>()
            ConcurrentMap<String, Advice> mapRef = advices.putIfAbsent(appId, current)
            if (mapRef != null)
            {
                current = mapRef
            }
        }

        current[advice.getName() + '/' + wildcard] = advice

        // Apply newly added advice to any fully loaded (hydrated) cubes.
        String regex = StringUtilities.wildcardToRegexString(wildcard)
        Map<String, Object> cubes = getCacheForApp(appId)

        for (Object value : cubes.values())
        {
            if (value instanceof NCube)
            {   // apply advice to hydrated cubes
                NCube ncube = value as NCube
                Axis axis = ncube.getAxis('method')
                addAdviceToMatchedCube(advice, regex, ncube, axis)
            }
        }
    }

    private static void addAdviceToMatchedCube(Advice advice, String regex, NCube ncube, Axis axis)
    {
        if (axis != null)
        {   // Controller methods
            for (Column column : axis.getColumnsWithoutDefault())
            {
                String method = column.getValue().toString()
                String classMethod = ncube.getName() + '.' + method + '()'
                if (classMethod.matches(regex))
                {
                    ncube.addAdvice(advice, method)
                }
            }
        }
        else
        {   // Expressions
            String classMethod = ncube.getName() + '.run()'
            if (classMethod.matches(regex))
            {
                ncube.addAdvice(advice, 'run')
            }
        }
    }

    /**
     * Apply existing advices loaded into the NCubeManager, to the passed in
     * n-cube.  This allows advices to be added first, and then let them be
     * applied 'on demand' as an n-cube is loaded later.
     * @param appId ApplicationID
     * @param ncube NCube to which all matching advices will be applied.
     */
    private static void applyAdvices(ApplicationID appId, NCube ncube)
    {
        final Map<String, Advice> appAdvices = advices[appId]

        if (MapUtilities.isEmpty(appAdvices))
        {
            return
        }
        for (Map.Entry<String, Advice> entry : appAdvices.entrySet())
        {
            final Advice advice = entry.getValue()
            final String wildcard = entry.getKey().replace(advice.getName() + '/', "")
            final String regex = StringUtilities.wildcardToRegexString(wildcard)
            final Axis axis = ncube.getAxis('method')
            addAdviceToMatchedCube(advice, regex, ncube, axis)
        }
    }

    /**
     * Retrieve all cube names that are deeply referenced by ApplicationID + n-cube name.
     */
    static void getReferencedCubeNames(ApplicationID appId, String name, Set<String> refs)
    {
        if (refs == null)
        {
            throw new IllegalArgumentException('Could not get referenced cube names, null passed in for Set to hold referenced n-cube names, app: ' + appId + ', n-cube: ' + name)
        }
        validateAppId(appId)
        NCube.validateCubeName(name)
        NCube ncube = getCube(appId, name)
        if (ncube == null)
        {
            throw new IllegalArgumentException('Could not get referenced cube names, n-cube: ' + name + ' does not exist in app: ' + appId)
        }
        Set<String> subCubeList = ncube.getReferencedCubeNames()

        // TODO: Use explicit stack, NOT recursion

        for (String cubeName : subCubeList)
        {
            if (!refs.contains(cubeName))
            {
                refs.add(cubeName)
                getReferencedCubeNames(appId, cubeName, refs)
            }
        }
    }

    /**
     * Get List<NCubeInfoDto> of n-cube record DTOs for the given ApplicationID (branch only).  If using
     * For any cube record loaded, for which there is no entry in the app's cube cache, an entry
     * is added mapping the cube name to the cube record (NCubeInfoDto).  This will be replaced
     * by an NCube if more than the name is required.
     * one (1) character.  This is universal whether using a SQL perister or Mongo persister.
     */
    static List<NCubeInfoDto> getBranchChangesFromDatabase(ApplicationID appId, String otherBranch = ApplicationID.HEAD)
    {
        validateAppId(appId)
        if (appId.getBranch().equals(ApplicationID.HEAD))
        {
            throw new IllegalArgumentException('Cannot get branch changes from HEAD')
        }

        // TODO: Need to get all 'appId' cubes
        // TODO: Need to get all otherBranch cubes
        // TODO: figure out:
        // TODO: 1. How many you've added
        // TODO: 2. How many you've deleted
        // TODO: 3. How many you've changed
        // TODO: 4. How many they've changed
        // TODO: so on and so on.
        ApplicationID otherBranchId = appId.asBranch(otherBranch)
        Map<String, NCubeInfoDto> headMap = new TreeMap<>()

        List<NCubeInfoDto> branchList = search(appId, null, null, [(SEARCH_CHANGED_RECORDS_ONLY):true])
        List<NCubeInfoDto> otherBranchList = search(otherBranchId, null, null, [(SEARCH_ACTIVE_RECORDS_ONLY):false])
        List<NCubeInfoDto> list = []

        //  build map of head objects for reference.
        for (NCubeInfoDto info : otherBranchList)
        {
            headMap[info.name] = info
        }

        // Loop through changed (added, deleted, created, restored, updated) records
        for (NCubeInfoDto info : branchList)
        {
            long revision = (long) Converter.convert(info.revision, long.class)
            NCubeInfoDto head = headMap[info.name]

            if (head == null)
            {
                if (revision >= 0)
                {
                    info.changeType = ChangeType.CREATED.getCode()
                    list.add(info)
                }
            }
            else if (info.headSha1 == null)
            {   //  we created this guy locally
                // someone added this one to the head already
                info.changeType = ChangeType.CONFLICT.getCode()
                list.add(info)
            }
            else
            {
                if (StringUtilities.equalsIgnoreCase(info.headSha1, head.sha1))
                {
                    if (StringUtilities.equalsIgnoreCase(info.sha1, info.headSha1))
                    {
                        // only net change could be revision deleted or restored.  check head.
                        long headRev = Long.parseLong(head.revision)

                        if (headRev < 0 != revision < 0)
                        {
                            if (revision < 0)
                            {
                                info.changeType = ChangeType.DELETED.getCode()
                            }
                            else
                            {
                                info.changeType = ChangeType.RESTORED.getCode()
                            }

                            list.add(info)
                        }
                    }
                    else
                    {
                        info.changeType = ChangeType.UPDATED.getCode()
                        list.add(info)
                    }
                }
                else
                {
                    info.changeType = ChangeType.CONFLICT.getCode()
                    list.add(info)
                }
            }
        }

        return list
    }

    /**
     * Restore a previously deleted n-cube.
     */
    static void restoreCubes(ApplicationID appId, Object[] cubeNames, String username = getUserId())
    {
        validateAppId(appId)
        appId.validateBranchIsNotHead()

        if (appId.isRelease())
        {
            throw new IllegalArgumentException(ReleaseStatus.RELEASE.name() + ' cubes cannot be restored, app: ' + appId)
        }

        if (ArrayUtilities.isEmpty(cubeNames))
        {
            throw new IllegalArgumentException('Error, empty array of cube names passed in to be restored.')
        }

        assertNotLockBlocked(appId)
        for (String cubeName : cubeNames)
        {
            assertPermissions(appId, cubeName, ACTION.UPDATE)
        }

        // Batch restore
        getPersister().restoreCubes(appId, cubeNames, username)

        // Load cache
        for (Object name : cubeNames)
        {
            if ((name instanceof String))
            {
                String cubeName = name as String
                NCube.validateCubeName(cubeName)
                NCube ncube = getPersister().loadCube(appId, cubeName)
                addCube(appId, ncube)
            }
            else
            {
                throw new IllegalArgumentException('Non string name given for cube to restore: ' + name)
            }
        }
    }

    /**
     * Get a List<NCubeInfoDto> containing all history for the given cube.
     */
    static List<NCubeInfoDto> getRevisionHistory(ApplicationID appId, String cubeName)
    {
        validateAppId(appId)
        NCube.validateCubeName(cubeName)
        assertPermissions(appId, cubeName)
        List<NCubeInfoDto> revisions = getPersister().getRevisions(appId, cubeName)
        return revisions
    }

    /**
     * Return a List of Strings containing all unique App names for the given tenant.
     */
    static List<String> getAppNames(String tenant)
    {
        return getPersister().getAppNames(tenant)
    }

    /**
     * Get all of the versions that exist for the given ApplicationID (tenant and app).
     * @return List<String> version numbers.
     */
    static Map<String, List<String>> getVersions(String tenant, String app)
    {
        ApplicationID.validateTenant(tenant)
        ApplicationID.validateApp(app)
        return getPersister().getVersions(tenant, app)
    }

    /**
     * Duplicate the given n-cube specified by oldAppId and oldName to new ApplicationID and name,
     */
    static void duplicate(ApplicationID oldAppId, ApplicationID newAppId, String oldName, String newName, String username = getUserId())
    {
        validateAppId(oldAppId)
        validateAppId(newAppId)

        newAppId.validateBranchIsNotHead()

        if (newAppId.isRelease())
        {
            throw new IllegalArgumentException('Cubes cannot be duplicated into a ' + ReleaseStatus.RELEASE + ' version, cube: ' + newName + ', app: ' + newAppId)
        }

        NCube.validateCubeName(oldName)
        NCube.validateCubeName(newName)

        if (oldName.equalsIgnoreCase(newName) && oldAppId.equals(newAppId))
        {
            throw new IllegalArgumentException('Could not duplicate, old name cannot be the same as the new name when oldAppId matches newAppId, name: ' + oldName + ', app: ' + oldAppId)
        }

        assertPermissions(oldAppId, oldName, ACTION.READ)
        if (!oldAppId.equals(newAppId))
        {   // Only see if branch permissions are needed to be created when destination cube is in a different ApplicationID
            detectNewAppId(newAppId)
        }
        assertPermissions(newAppId, newName, ACTION.UPDATE)
        assertNotLockBlocked(newAppId)
        getPersister().duplicateCube(oldAppId, newAppId, oldName, newName, username)

        if (CLASSPATH_CUBE.equalsIgnoreCase(newName))
        {   // If another cube is renamed into sys.classpath,
            // then the entire class loader must be dropped (and then lazily rebuilt).
            clearCache(newAppId)
        }
        else
        {
            removeCachedCube(newAppId, newName)
        }

        broadcast(newAppId)
    }

    /**
     * Update the passed in NCube.  Only SNAPSHOT cubes can be updated.
     *
     * @param ncube      NCube to be updated.
     * @return boolean true on success, false otherwise
     */
    static boolean updateCube(ApplicationID appId, NCube ncube, boolean createPermCubesIfNeeded = false)
    {
        validateAppId(appId)
        validateCube(ncube)

        if (appId.isRelease())
        {
            throw new IllegalArgumentException(ReleaseStatus.RELEASE.name() + ' cubes cannot be updated, cube: ' + ncube.getName() + ', app: ' + appId)
        }

        appId.validateBranchIsNotHead()

        final String cubeName = ncube.getName()
        if (createPermCubesIfNeeded)
        {
            detectNewAppId(appId)
        }
        assertPermissions(appId, cubeName, ACTION.UPDATE)
        assertNotLockBlocked(appId)
        getPersister().updateCube(appId, ncube, getUserId())
        ncube.setApplicationID(appId)

        if (CLASSPATH_CUBE.equalsIgnoreCase(cubeName))
        {   // If the sys.classpath cube is changed, then the entire class loader must be dropped.  It will be lazily rebuilt.
            clearCache(appId)
        }

        addCube(appId, ncube)
        broadcast(appId)
        return true
    }

    /**
     * Copy branch from one app id to another
     * @param srcAppId Branch copied from (source branch)
     * @param targetAppId Branch copied to (must not exist)
     * @return int number of n-cubes in branch (number copied - revision depth is not copied)
     */
    static int copyBranch(ApplicationID srcAppId, ApplicationID targetAppId)
    {
        validateAppId(srcAppId)
        validateAppId(targetAppId)
        targetAppId.validateStatusIsNotRelease()
        assertNotLockBlocked(targetAppId)
        int rows = getPersister().copyBranch(srcAppId, targetAppId)
        if (!targetAppId.isHead())
        {
            addBranchPermissionsCube(targetAppId);
        }
        clearCache(targetAppId)
        broadcast(targetAppId)
        return rows
    }

    static int mergeAcceptMine(ApplicationID appId, Object[] cubeNames, String username = getUserId())
    {
        validateAppId(appId)
        appId.validateBranchIsNotHead()
        appId.validateStatusIsNotRelease()
        int count = 0

        assertNotLockBlocked(appId)
        for (Object cubeName : cubeNames)
        {
            String cubeNameStr = cubeName as String
            assertPermissions(appId, cubeNameStr, ACTION.UPDATE)
            getPersister().mergeAcceptMine(appId, cubeNameStr, username)
            removeCachedCube(appId, cubeNameStr)
            count++
        }
        return count
    }

    static int mergeAcceptTheirs(ApplicationID appId, Object[] cubeNames, Object[] branchSha1, String username = getUserId())
    {
        validateAppId(appId)
        appId.validateBranchIsNotHead()
        appId.validateStatusIsNotRelease()
        assertNotLockBlocked(appId)
        int count = 0

        for (int i = 0; i < cubeNames.length; i++)
        {
            String cubeNameStr = cubeNames[i] as String
            String sha1 = branchSha1[i] as String
            assertPermissions(appId, cubeNameStr, ACTION.UPDATE)
            getPersister().mergeAcceptTheirs(appId, cubeNameStr, sha1, username)
            removeCachedCube(appId, cubeNameStr)
            count++
        }

        return count
    }

    /**
     * Commit the passed in changed cube records identified by NCubeInfoDtos.
     * @return array of NCubeInfoDtos that are to be committed.
     */
    static List<NCubeInfoDto> commitBranch(ApplicationID appId, Object[] infoDtos, String username = getUserId())
    {
        validateAppId(appId)
        appId.validateBranchIsNotHead()
        appId.validateStatusIsNotRelease()
        assertNotLockBlocked(appId)

        ApplicationID headAppId = appId.asHead()
        Map<String, NCubeInfoDto> headMap = new TreeMap<>()
        List<NCubeInfoDto> headInfo = search(headAppId, null, null, [(SEARCH_ACTIVE_RECORDS_ONLY):true])

        //  build map of head objects for reference.
        for (NCubeInfoDto info : headInfo)
        {
            headMap[info.name] = info
        }

        List<NCubeInfoDto> dtosToUpdate = []
        List<NCubeInfoDto> dtosMerged = []

        Map<String, Map> errors = [:]

        for (Object dto : infoDtos)
        {
            NCubeInfoDto branchCubeInfo = (NCubeInfoDto)dto

            if (!branchCubeInfo.isChanged())
            {
                continue
            }
            assertPermissions(appId, branchCubeInfo.name, ACTION.COMMIT)
            if (branchCubeInfo.sha1 == null)
            {
                branchCubeInfo.sha1 = ""
            }

            // All changes go through here.
            NCubeInfoDto headCubeInfo = headMap[branchCubeInfo.name]
            long infoRev = (long) Converter.convert(branchCubeInfo.revision, long.class)

            if (headCubeInfo == null)
            {   // No matching head cube, CREATE case
                if (infoRev >= 0)
                {   // Only create if the cube in the branch is active (revision number not negative)
                    dtosToUpdate.add(branchCubeInfo)
                }
            }
            else if (StringUtilities.equalsIgnoreCase(branchCubeInfo.headSha1, headCubeInfo.sha1))
            {   // HEAD cube has not changed (at least in terms of SHA-1 it could have it's revision sign changed)
                if (StringUtilities.equalsIgnoreCase(branchCubeInfo.sha1, branchCubeInfo.headSha1))
                {   // Cubes are same, but active status could be opposite (delete or restore case)
                    long headRev = (long) Converter.convert(headCubeInfo.revision, long.class)
                    if ((infoRev < 0) != (headRev < 0))
                    {
                        dtosToUpdate.add(branchCubeInfo)
                    }
                }
                else
                {   // Regular update case (branch updated cube that was not touched in HEAD)
                    dtosToUpdate.add(branchCubeInfo)
                }
            }
            else if (StringUtilities.equalsIgnoreCase(branchCubeInfo.sha1, headCubeInfo.sha1))
            {   // Branch headSha1 does not match HEAD sha1, but it's SHA-1 matches the HEAD SHA-1.
                // This means that the branch cube and HEAD cube are identical, but the HEAD was
                // different when the branch was created.
                dtosToUpdate.add(branchCubeInfo)
            }
            else
            {
                String msg
                if (branchCubeInfo.headSha1 == null)
                {
                    msg = '. A cube with the same name was added to HEAD since your branch was created.'
                }
                else
                {
                    msg = '. The cube changed since your last update branch.'
                }
                String message = "Conflict merging " + branchCubeInfo.name + msg
                NCube mergedCube = checkForConflicts(appId, errors, message, branchCubeInfo, headCubeInfo, false)
                if (mergedCube != null)
                {
                    NCubeInfoDto mergedDto = getPersister().commitMergedCubeToHead(appId, mergedCube, username)
                    dtosMerged.add(mergedDto)
                }
            }
        }

        if (!errors.isEmpty())
        {
            throw new BranchMergeException(errors.size() + ' merge conflict(s) committing branch.  Update your branch and retry commit.', errors)
        }

        List<NCubeInfoDto> committedCubes = new ArrayList<>(dtosToUpdate.size())
        Object[] ids = new Object[dtosToUpdate.size()]
        int i=0
        for (NCubeInfoDto dto : dtosToUpdate)
        {
            ids[i++] = dto.id
        }

        committedCubes.addAll(getPersister().commitCubes(appId, ids, username))
        committedCubes.addAll(dtosMerged)
        clearCache(appId)
        clearCache(headAppId)
        broadcast(appId)
        return committedCubes
    }

    private static NCube checkForConflicts(ApplicationID appId, Map<String, Map> errors, String message, NCubeInfoDto info, NCubeInfoDto head, boolean reverse)
    {
        Map<String, Object> map = [:]
        map.message = message
        map.sha1 = info.sha1
        map.headSha1 = head != null ? head.sha1 : null

        try
        {
            if (head != null)
            {
                long branchCubeId = (long) Converter.convert(info.id, long.class)
                long headCubeId = (long) Converter.convert(head.id, long.class)
                NCube branchCube = getPersister().loadCubeById(branchCubeId)
                NCube headCube = getPersister().loadCubeById(headCubeId)

                if (info.headSha1 != null)
                {
                    NCube baseCube = getPersister().loadCubeBySha1(appId, info.name, info.headSha1)

                    Map branchDelta = DeltaProcessor.getDelta(baseCube, branchCube)
                    Map headDelta = DeltaProcessor.getDelta(baseCube, headCube)

                    if (DeltaProcessor.areDeltaSetsCompatible(branchDelta, headDelta, reverse))
                    {
                        if (reverse)
                        {   // Updating Branch with what is in HEAD
                            DeltaProcessor.mergeDeltaSet(headCube, branchDelta)
                            return headCube
                        }
                        else
                        {   // Updating HEAD with what is in branch
                            DeltaProcessor.mergeDeltaSet(branchCube, headDelta)
                            return branchCube
                        }
                    }
                }

                List<Delta> diff = DeltaProcessor.getDeltaDescription(branchCube, headCube)
                if (diff.size() > 0)
                {
                    map.diff = diff
                }
                else
                {
                    return branchCube
                }
            }
            else
            {
                map.diff = null
            }
        }
        catch (Exception e)
        {
            Delta delta = new Delta(Delta.Location.NCUBE, Delta.Type.UPDATE, e.message)
            map.diff = [delta]
        }
        errors[info.name] = map
        return null
    }

    private static NCube attemptMerge(ApplicationID appId, Map<String, Map> errors, String message, NCubeInfoDto info, NCubeInfoDto other)
    {
        Map<String, Object> map = [:]
        map.message = message
        map.sha1 = info.sha1
        map.headSha1 = info.headSha1

        long branchCubeId = (long) Converter.convert(info.id, long.class)
        long otherCubeId = (long) Converter.convert(other.id, long.class)
        NCube branchCube = getPersister().loadCubeById(branchCubeId)
        NCube otherCube = getPersister().loadCubeById(otherCubeId)
        NCube<?> baseCube

        if (info.headSha1 != null)
        {   // Treat both as based on the same HEAD cube
            baseCube = getPersister().loadCubeBySha1(appId, info.name, info.headSha1)
        }
        else
        {   // Treat both as based on the same cube with same axes, no columns and no cells
            // This causes a complete 'build-up' delta.  If they are both merge compatible,
            // then they will merge.  This allows a new cube that has not yet been committed
            // to HEAD to be merged into.
            baseCube = branchCube.duplicate(info.name)
            baseCube.clearCells()
            for (Axis axis : baseCube.getAxes())
            {
                axis.clear()
            }
        }

        Map branchDelta = DeltaProcessor.getDelta(baseCube, branchCube)
        Map otherBranchDelta = DeltaProcessor.getDelta(baseCube, otherCube)

        if (DeltaProcessor.areDeltaSetsCompatible(branchDelta, otherBranchDelta, false))
        {
            DeltaProcessor.mergeDeltaSet(otherCube, branchDelta)
            return otherCube
        }

        List<Delta> diff = DeltaProcessor.getDeltaDescription(branchCube, otherCube)
        if (diff.size() > 0)
        {
            map.diff = diff
        }
        else
        {
            return branchCube
        }
        errors[info.name] = map
        return null
    }

    /**
     * Rollback the passed in list of n-cubes.  Each one will be returned to the state is was
     * when the branch was created.  This is an insert cube (maintaining revision history) for
     * each cube passed in.
     */
    static int rollbackCubes(ApplicationID appId, Object[] names, String username = getUserId())
    {
        validateAppId(appId)
        appId.validateBranchIsNotHead()
        appId.validateStatusIsNotRelease()
        assertNotLockBlocked(appId)

        for (Object name : names)
        {
            String cubeName = name as String
            assertPermissions(appId, cubeName, ACTION.UPDATE)
        }
        int count = getPersister().rollbackCubes(appId, names, username)
        clearCache(appId)
        return count
    }

    /**
     * Update a branch cube the passed in branch.  It can be the String 'HEAD' or the name of any branch.  The
     * cube with the passed in name will have the content from a cube with the same name, in the passed in branch,
     * merged into itself and persisted.
     */
    static Map<String, Object> updateBranchCube(ApplicationID appId, String cubeName, String branch, String username = getUserId())
    {
        validateAppId(appId)
        appId.validateBranchIsNotHead()
        appId.validateStatusIsNotRelease()
        assertNotLockBlocked(appId)
        assertPermissions(appId, cubeName, ACTION.UPDATE)

        ApplicationID srcAppId = appId.asBranch(branch)

        Map<String, Object> options = [:]
        options[(SEARCH_ACTIVE_RECORDS_ONLY)] = false
        options[(SEARCH_EXACT_MATCH_NAME)] = true
        List<NCubeInfoDto> records = search(appId, cubeName, null, options)
        List<NCubeInfoDto> srcRecords = search(srcAppId, cubeName, null, options)

        List<NCubeInfoDto> updates = []
        List<NCubeInfoDto> dtosMerged = []
        Map<String, Map> conflicts = new CaseInsensitiveMap<>()
        Map<String, Object> ret = [:]

        ret[BRANCH_MERGES] = dtosMerged
        ret[BRANCH_CONFLICTS] = conflicts

        if (records.isEmpty() || srcRecords.isEmpty())
        {
            ret[BRANCH_UPDATES] = []
            return ret
        }
        if (records.size() > 1)
        {
            throw new IllegalArgumentException('Name passed in matches more than one n-cube, no update performed. Name: ' + cubeName + ', app: ' + appId)
        }
        if (srcRecords.size() > 1)
        {
            throw new IllegalArgumentException('Name passed in matches more than one n-cube in branch (' + branch + '), no update performed. Name: ' + cubeName + ', app: ' + appId)
        }

        NCubeInfoDto srcDto = srcRecords[0]     // Exact match, only 1 (See check right above here)
        NCubeInfoDto info = records[0]          // ditto

        long infoRev = Converter.convert(info.revision, long.class) as long
        long srcRev = Converter.convert(srcDto.revision, long.class) as long
        boolean activeStatusMatches = (infoRev < 0) == (srcRev < 0)

        if (branch.equalsIgnoreCase(ApplicationID.HEAD))
        {   // Update from HEAD branch is done differently than update from neighbor branch
            // Did branch change?
            if (!info.isChanged())
            {   // No change on branch
                if (!activeStatusMatches || !StringUtilities.equalsIgnoreCase(info.headSha1, srcDto.sha1))
                {   // 1. The active/deleted statuses don't match, or
                    // 2. HEAD has different SHA1 but branch cube did not change, safe to update branch (fast forward)
                    // In both cases, the cube was marked NOT changed in the branch, so safe to update.
                    updates.add(srcDto)
                }
            }
            else if (StringUtilities.equalsIgnoreCase(info.sha1, srcDto.sha1))
            {   // If branch is 'changed' but has same SHA-1 as head, then see if branch needs Fast-Forward
                if (!StringUtilities.equalsIgnoreCase(info.headSha1, srcDto.sha1))
                {   // Fast-Forward branch
                    // Update HEAD SHA-1 on branch directly (no need to insert)
                    getPersister().updateBranchCubeHeadSha1((Long) Converter.convert(info.id, Long.class), srcDto.sha1)
                }
            }
            else
            {
                if (!StringUtilities.equalsIgnoreCase(info.headSha1, srcDto.sha1))
                {   // Cube is different than HEAD, AND it is not based on same HEAD cube, but it could be merge-able.
                    String message = 'Cube was changed in both branch and HEAD'
                    NCube cube = checkForConflicts(appId, conflicts, message, info, srcDto, true)

                    if (cube != null)
                    {
                        NCubeInfoDto mergedDto = getPersister().commitMergedCubeToBranch(appId, cube, srcDto.sha1, username)
                        dtosMerged.add(mergedDto)
                    }
                }
            }
        }
        else
        {
            if (!StringUtilities.equalsIgnoreCase(info.sha1, srcDto.sha1))
            {   // Different SHA-1's
                String message = 'Cube in ' + appId.getBranch() + ' conflicts with cube in ' + branch
                NCube cube = attemptMerge(appId, conflicts, message, info, srcDto)

                if (cube != null)
                {
                    NCubeInfoDto mergedDto = getPersister().commitMergedCubeToBranch(appId, cube, info.headSha1, username)
                    dtosMerged.add(mergedDto)
                }
            }
        }

        List<NCubeInfoDto> finalUpdates = new ArrayList<>(updates.size())

        Object[] ids = new Object[updates.size()]
        int i=0
        for (NCubeInfoDto dto : updates)
        {
            ids[i++] = dto.id
        }
        finalUpdates.addAll(getPersister().pullToBranch(appId, ids, username))
        clearCache(appId)
        ret[BRANCH_UPDATES] = finalUpdates
        return ret
    }

    /**
     * Update a branch from the HEAD.  Changes from the HEAD are merged into the
     * supplied branch.  If the merge cannot be done perfectly, an exception is
     * thrown indicating the cubes that are in conflict.
     */
    static Map<String, Object> updateBranch(ApplicationID appId, String username = getUserId())
    {
        validateAppId(appId)
        appId.validateBranchIsNotHead()
        appId.validateStatusIsNotRelease()
        assertNotLockBlocked(appId)
        assertPermissions(appId, null, ACTION.UPDATE)

        ApplicationID headAppId = appId.asHead()

        List<NCubeInfoDto> records = search(appId, null, null, [(SEARCH_ACTIVE_RECORDS_ONLY):false])
        if (records.isEmpty())
        {
            throw new IllegalArgumentException(appId.app + ' ' + appId.version + '-' + appId.status + ' (' + appId.branch + ' branch) is empty and cannot be updated.  Create branch first.')
        }
        Map<String, NCubeInfoDto> branchRecordMap = new CaseInsensitiveMap<>()

        for (NCubeInfoDto info : records)
        {
            branchRecordMap[info.name] = info
        }

        List<NCubeInfoDto> updates = []
        List<NCubeInfoDto> dtosMerged = []
        Map<String, Map> conflicts = new CaseInsensitiveMap<>()
        List<NCubeInfoDto> headRecords = search(headAppId, null, null, [(SEARCH_ACTIVE_RECORDS_ONLY):false])

        for (NCubeInfoDto head : headRecords)
        {
            NCubeInfoDto info = branchRecordMap[head.name]

            if (info == null)
            {   // HEAD has cube that branch does not have
                updates.add(head)
                continue
            }

            long infoRev = (long) Converter.convert(info.revision, long.class)
            long headRev = (long) Converter.convert(head.revision, long.class)
            boolean activeStatusMatches = (infoRev < 0) == (headRev < 0)

            // Did branch change?
            if (!info.isChanged())
            {   // No change on branch
                if (!activeStatusMatches || !StringUtilities.equalsIgnoreCase(info.headSha1, head.sha1))
                {   // 1. The active/deleted statuses don't match, or
                    // 2. HEAD has different SHA1 but branch cube did not change, safe to update branch (fast forward)
                    // In both cases, the cube was marked NOT changed in the branch, so safe to update.
                    updates.add(head)
                }
            }
            else if (StringUtilities.equalsIgnoreCase(info.sha1, head.sha1))
            {   // If branch is 'changed' but has same SHA-1 as head, then see if branch needs Fast-Forward
                if (!StringUtilities.equalsIgnoreCase(info.headSha1, head.sha1))
                {   // Fast-Forward branch
                    // Update HEAD SHA-1 on branch directly (no need to insert)
                    getPersister().updateBranchCubeHeadSha1((Long) Converter.convert(info.id, Long.class), head.sha1)
                }
            }
            else
            {
                if (!StringUtilities.equalsIgnoreCase(info.headSha1, head.sha1))
                {   // Cube is different than HEAD, AND it is not based on same HEAD cube, but it could be merge-able.
                    String message = 'Cube was changed in both branch and HEAD'
                    NCube cube = checkForConflicts(appId, conflicts, message, info, head, true)

                    if (cube != null)
                    {
                        NCubeInfoDto mergedDto = getPersister().commitMergedCubeToBranch(appId, cube, head.sha1, username)
                        dtosMerged.add(mergedDto)
                    }
                }
            }
        }

        List<NCubeInfoDto> finalUpdates = new ArrayList<>(updates.size())

        Object[] ids = new Object[updates.size()]
        int i=0
        for (NCubeInfoDto dto : updates)
        {
            ids[i++] = dto.id
        }
        finalUpdates.addAll(getPersister().pullToBranch(appId, ids, username))

        clearCache(appId)

        Map<String, Object> ret = [:]
        ret[BRANCH_UPDATES] = finalUpdates
        ret[BRANCH_MERGES] = dtosMerged
        ret[BRANCH_CONFLICTS] = conflicts
        return ret
    }

    /**
     * Move the branch specified in the appId to the newer snapshot version (newSnapVer).
     * @param ApplicationID indicating what to move
     * @param newSnapVer String version to move cubes to
     * @return number of rows moved (count includes revisions per cube).
     */
    static int moveBranch(ApplicationID appId, String newSnapVer)
    {
        validateAppId(appId)
        if (ApplicationID.HEAD == appId.branch)
        {
            throw new IllegalArgumentException('Cannot move the HEAD branch')
        }
        if ('0.0.0' == appId.version)
        {
            throw new IllegalStateException(ERROR_CANNOT_MOVE_000)
        }
        if ('0.0.0' == newSnapVer)
        {
            throw new IllegalStateException(ERROR_CANNOT_MOVE_TO_000)
        }
        assertLockedByMe(appId)
        assertPermissions(appId, null, ACTION.RELEASE)
        int rows = getPersister().moveBranch(appId, newSnapVer)
        clearCacheForBranches(appId)
        //TODO:  Does broadcast need to send all branches that have changed as a result of this?
        broadcast(appId)
        return rows
    }

    /**
     * Perform release (SNAPSHOT to RELEASE) for the given ApplicationIDs n-cubes.
     */
    static int releaseVersion(ApplicationID appId, String newSnapVer)
    {
        validateAppId(appId)
        assertPermissions(appId, null, ACTION.RELEASE)
        assertLockedByMe(appId)
        ApplicationID.validateVersion(newSnapVer)
        if ('0.0.0' == appId.version)
        {
            throw new IllegalArgumentException(ERROR_CANNOT_RELEASE_000)
        }
        if ('0.0.0' == newSnapVer)
        {
            throw new IllegalArgumentException(ERROR_CANNOT_RELEASE_TO_000)
        }
        if (search(appId.asRelease(), null, null, [(SEARCH_ACTIVE_RECORDS_ONLY):true]).size() != 0)
        {
            throw new IllegalArgumentException("A RELEASE version " + appId.version + " already exists, app: " + appId)
        }

        int rows = getPersister().releaseCubes(appId, newSnapVer)
        clearCacheForBranches(appId)
        //TODO:  Does broadcast need to send all branches that have changed as a result of this?
        broadcast(appId)
        return rows
    }

    /**
     * Perform release (SNAPSHOT to RELEASE) for the given ApplicationIDs n-cubes.
     */
    static int releaseCubes(ApplicationID appId, String newSnapVer)
    {
        assertPermissions(appId, null, ACTION.RELEASE)
        validateAppId(appId)
        ApplicationID.validateVersion(newSnapVer)
        if ('0.0.0' == appId.version)
        {
            throw new IllegalArgumentException(ERROR_CANNOT_RELEASE_000)
        }
        if ('0.0.0' == newSnapVer)
        {
            throw new IllegalArgumentException(ERROR_CANNOT_RELEASE_TO_000)
        }
        if (search(appId.asVersion(newSnapVer), null, null, [(SEARCH_ACTIVE_RECORDS_ONLY):true]).size() != 0)
        {
            throw new IllegalArgumentException("A SNAPSHOT version " + appId.version + " already exists, app: " + appId)
        }
        if (search(appId.asRelease(), null, null, [(SEARCH_ACTIVE_RECORDS_ONLY):true]).size() != 0)
        {
            throw new IllegalArgumentException("A RELEASE version " + appId.version + " already exists, app: " + appId)
        }

        lockApp(appId)
        if (!isJUnitTest())
        {   // Only sleep when running in production (not by JUnit)
            sleep(10000)
        }

        Set<String> branches = getBranches(appId)
        for (String branch : branches)
        {
            if (!ApplicationID.HEAD.equalsIgnoreCase(branch))
            {
                ApplicationID branchAppId = appId.asBranch(branch)
                moveBranch(branchAppId, newSnapVer)
            }
        }
        int rows = getPersister().releaseCubes(appId, newSnapVer)
        getPersister().copyBranch(appId.asRelease(), appId.asSnapshot().asHead().asVersion(newSnapVer))
        clearCacheForBranches(appId)
        //TODO:  Does broadcast need to send all branches that have changed as a result of this?
        broadcast(appId)
        unlockApp(appId)
        return rows
    }

    private static boolean isJUnitTest()
    {
        StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace()
        List<StackTraceElement> list = Arrays.asList(stackTrace)
        for (StackTraceElement element : list)
        {
            if (element.getClassName().startsWith('org.junit.'))
            {
                return true
            }
        }
        return false
    }

    static void changeVersionValue(ApplicationID appId, String newVersion)
    {
        validateAppId(appId)

        if (appId.isRelease())
        {
            throw new IllegalArgumentException('Cannot change the version of a ' + ReleaseStatus.RELEASE.name() + ' app, app: ' + appId)
        }
        ApplicationID.validateVersion(newVersion)
        assertPermissions(appId, null, ACTION.RELEASE)
        assertNotLockBlocked(appId)
        getPersister().changeVersionValue(appId, newVersion)
        clearCache(appId)
        clearCache(appId.asVersion(newVersion))
        broadcast(appId)
    }

    static boolean renameCube(ApplicationID appId, String oldName, String newName, String username = getUserId())
    {
        validateAppId(appId)
        appId.validateBranchIsNotHead()

        if (appId.isRelease())
        {
            throw new IllegalArgumentException('Cannot rename a ' + ReleaseStatus.RELEASE.name() + ' cube, cube: ' + oldName + ', app: ' + appId)
        }

        assertNotLockBlocked(appId)

        NCube.validateCubeName(oldName)
        NCube.validateCubeName(newName)

        if (oldName.equalsIgnoreCase(newName))
        {
            throw new IllegalArgumentException('Could not rename, old name cannot be the same as the new name, name: ' + oldName + ', app: ' + appId)
        }

        assertPermissions(appId, oldName, ACTION.UPDATE)
        assertPermissions(appId, newName, ACTION.UPDATE)

        boolean result = getPersister().renameCube(appId, oldName, newName, username)

        if (CLASSPATH_CUBE.equalsIgnoreCase(oldName) || CLASSPATH_CUBE.equalsIgnoreCase(newName))
        {   // If the sys.classpath cube is renamed, or another cube is renamed into sys.classpath,
            // then the entire class loader must be dropped (and then lazily rebuilt).
            clearCache(appId)
        }
        else
        {
            removeCachedCube(appId, oldName)
            removeCachedCube(appId, newName)
        }

        broadcast(appId)
        return result
    }

    static boolean deleteBranch(ApplicationID appId)
    {
        appId.validateBranchIsNotHead()
        assertPermissions(appId, null, ACTION.UPDATE)
        assertNotLockBlocked(appId)
        return getPersister().deleteBranch(appId)
    }

    /**
     * Delete the named NCube from the database
     *
     * @param cubeNames  Object[] of String cube names to be deleted (soft deleted)
     */
    static boolean deleteCubes(ApplicationID appId, Object[] cubeNames, String username = getUserId())
    {
        appId.validateBranchIsNotHead()
        assertNotLockBlocked(appId)
        for (Object name : cubeNames)
        {
            assertPermissions(appId, name as String, ACTION.UPDATE)
        }
        return deleteCubes(appId, cubeNames, false, username)
    }

    protected static boolean deleteCubes(ApplicationID appId, Object[] cubeNames, boolean allowDelete, String username = getUserId())
    {
        validateAppId(appId)
        if (!allowDelete)
        {
            if (appId.isRelease())
            {
                throw new IllegalArgumentException(ReleaseStatus.RELEASE.name() + ' cubes cannot be hard-deleted, app: ' + appId)
            }
        }

        assertNotLockBlocked(appId)
        for (Object name : cubeNames)
        {
            assertPermissions(appId, name as String, ACTION.UPDATE)
        }

        if (getPersister().deleteCubes(appId, cubeNames, allowDelete, username))
        {
            for (int i=0; i < cubeNames.length; i++)
            {
                removeCachedCube(appId, cubeNames[i] as String)
            }
            broadcast(appId)
            return true
        }
        return false
    }

    static boolean updateTestData(ApplicationID appId, String cubeName, String testData)
    {
        validateAppId(appId)
        NCube.validateCubeName(cubeName)
        assertPermissions(appId, cubeName, ACTION.UPDATE)
        assertNotLockBlocked(appId)
        return getPersister().updateTestData(appId, cubeName, testData)
    }

    static String getTestData(ApplicationID appId, String cubeName)
    {
        validateAppId(appId)
        NCube.validateCubeName(cubeName)
        assertPermissions(appId, cubeName)
        return getPersister().getTestData(appId, cubeName)
    }

    static boolean updateNotes(ApplicationID appId, String cubeName, String notes)
    {
        validateAppId(appId)
        NCube.validateCubeName(cubeName)
        assertPermissions(appId, cubeName, ACTION.UPDATE)
        assertNotLockBlocked(appId)
        return getPersister().updateNotes(appId, cubeName, notes)
    }

    static String getNotes(ApplicationID appId, String cubeName)
    {
        validateAppId(appId)
        NCube.validateCubeName(cubeName)
        assertPermissions(appId, cubeName)

        Map<String, Object> options = [:]
        options[SEARCH_INCLUDE_NOTES] = true
        options[SEARCH_EXACT_MATCH_NAME] = true
        List<NCubeInfoDto> infos = search(appId, cubeName, null, options)

        if (infos.isEmpty())
        {
            throw new IllegalArgumentException('Could not fetch notes, no cube: ' + cubeName + ' in app: ' + appId)
        }
        return infos[0].notes
    }

    static Set<String> getBranches(ApplicationID appId)
    {
        appId.validate()
        assertPermissions(appId, null)
        return getPersister().getBranches(appId)
    }

    static int getBranchCount(ApplicationID appId)
    {
        Set<String> branches = getBranches(appId)
        return branches.size()
    }

    static ApplicationID getApplicationID(String tenant, String app, Map<String, Object> coord)
    {
        ApplicationID.validateTenant(tenant)
        ApplicationID.validateApp(tenant)

        if (coord == null)
        {
            coord = [:]
        }

        NCube bootCube = getCube(ApplicationID.getBootVersion(tenant, app), SYS_BOOTSTRAP)

        if (bootCube == null)
        {
            throw new IllegalStateException('Missing ' + SYS_BOOTSTRAP + ' cube in the 0.0.0 version for the app: ' + app)
        }

        ApplicationID bootAppId = (ApplicationID) bootCube.getCell(coord)
        String version = bootAppId.getVersion()
        String status = bootAppId.getStatus()
        String branch = bootAppId.getBranch()

        if (!tenant.equalsIgnoreCase(bootAppId.getTenant()))
        {
            LOG.warn("sys.bootstrap cube for tenant '" + tenant + "', app '" + app + "' is returning a different tenant '" + bootAppId.getTenant() + "' than requested. Using '" + tenant + "' instead.")
        }

        if (!app.equalsIgnoreCase(bootAppId.getApp()))
        {
            LOG.warn("sys.bootstrap cube for tenant '" + tenant + "', app '" + app + "' is returning a different app '" + bootAppId.getApp() + "' than requested. Using '" + app + "' instead.")
        }

        return new ApplicationID(tenant, app, version, status, branch)
    }

    /**
     *
     * Fetch an array of NCubeInfoDto's where the cube names match the cubeNamePattern (contains) and
     * the content (in JSON format) 'contains' the passed in content String.
     * @param appId ApplicationID on which we are working
     * @param cubeNamePattern cubeNamePattern String pattern to match cube names
     * @param content String value that is 'contained' within the cube's JSON
     * @param options map with possible keys:
     *                changedRecordsOnly - default false ->  Only searches changed records if true.
     *                activeRecordsOnly - default false -> Only searches non-deleted records if true.
     *                deletedRecordsOnly - default false -> Only searches deleted records if true.
     *                cacheResult - default false -> Cache the cubes that match this result..
     * @return List<NCubeInfoDto>
     */
    static List<NCubeInfoDto> search(ApplicationID appId, String cubeNamePattern, String content, Map options)
    {
        validateAppId(appId)

        if (options == null)
        {
            options = [:]
        }

        Map permInfo = getPermInfo(appId)
        List<NCubeInfoDto> cubes = getPersister().search(appId, cubeNamePattern, content, options)
        if (!permInfo.skipPermCheck)
        {
            cubes.removeAll { !fastCheckPermissions(it.name, ACTION.READ, permInfo) }
        }
        return cubes
    }

    /**
     * This API will hand back a List of AxisRef, which is a complete description of a Reference
     * Axis pointer. It includes the Source ApplicationID, source Cube Name, source Axis Name,
     * and all the referenced cube/axis and filter (cube/method) parameters.
     * @param appId ApplicationID of the cube-set from which to fetch all the reference axes.
     * @return List<AxisRef>
     */
    static List<AxisRef> getReferenceAxes(ApplicationID appId)
    {
        validateAppId(appId)
        assertPermissions(appId, null)

        // Step 1: Fetch all NCubeInfoDto's for the passed in ApplicationID
        List<NCubeInfoDto> list = getPersister().search(appId, null, null, [(SEARCH_ACTIVE_RECORDS_ONLY):true])
        List<AxisRef> refAxes = []

        for (NCubeInfoDto dto : list)
        {
            try
            {
                NCube source = getPersister().loadCubeById(dto.id as long)
                for (Axis axis : source.getAxes())
                {
                    if (axis.isReference())
                    {
                        AxisRef ref = new AxisRef()
                        ref.srcAppId = appId
                        ref.srcCubeName = source.name
                        ref.srcAxisName = axis.name

                        ApplicationID refAppId = axis.getReferencedApp()
                        ref.destApp = refAppId.app
                        ref.destVersion = refAppId.version
                        ref.destCubeName = axis.getMetaProperty(ReferenceAxisLoader.REF_CUBE_NAME)
                        ref.destAxisName = axis.getMetaProperty(ReferenceAxisLoader.REF_AXIS_NAME)

                        ApplicationID transformAppId = axis.getTransformApp()
                        if (transformAppId)
                        {
                            ref.transformApp = transformAppId.app
                            ref.transformVersion = transformAppId.version
                            ref.transformCubeName = axis.getMetaProperty(ReferenceAxisLoader.TRANSFORM_CUBE_NAME)
                            ref.transformMethodName = axis.getMetaProperty(ReferenceAxisLoader.TRANSFORM_METHOD_NAME)
                        }

                        refAxes.add(ref)
                    }
                }
            }
            catch (Exception e)
            {
                LOG.warn('Unable to load cube: ' + dto.name + ', app: ' + dto.applicationID, e)
            }
        }
        return refAxes
    }

    static void updateReferenceAxes(List<AxisRef> axisRefs, String username = getUserId())
    {
        Set<ApplicationID> uniqueAppIds = new HashSet()
        for (AxisRef axisRef : axisRefs)
        {
            ApplicationID srcApp = axisRef.getSrcAppId()
            validateAppId(srcApp)
            assertPermissions(srcApp, axisRef.srcCubeName, ACTION.UPDATE)
            uniqueAppIds.add(srcApp)
            ApplicationID destAppId = new ApplicationID(srcApp.getTenant(), axisRef.getDestApp(), axisRef.getDestVersion(), ReleaseStatus.RELEASE.name(), ApplicationID.HEAD)
            validateAppId(destAppId)
            assertPermissions(destAppId, axisRef.destCubeName)

            if (axisRef.getTransformApp() != null && axisRef.getTransformVersion() != null)
            {
                ApplicationID transformAppId = new ApplicationID(srcApp.getTenant(), axisRef.getTransformApp(), axisRef.getTransformVersion(), ReleaseStatus.RELEASE.name(), ApplicationID.HEAD)
                validateAppId(transformAppId)
                assertPermissions(transformAppId, axisRef.transformCubeName, ACTION.READ)
            }
            removeCachedCube(srcApp, axisRef.getSrcCubeName())
        }

        // Make sure we are not lock blocked on any of the appId's that are being updated.
        for (ApplicationID appId : uniqueAppIds)
        {
            assertNotLockBlocked(appId)
        }

        for (AxisRef axisRef : axisRefs)
        {
            axisRef.with {
                NCube ncube = getPersister().loadCube(srcAppId, srcCubeName)
                Axis axis = ncube.getAxis(srcAxisName)

                if (axis.isReference())
                {
                    axis.setMetaProperty(ReferenceAxisLoader.REF_APP, destApp)
                    axis.setMetaProperty(ReferenceAxisLoader.REF_VERSION, destVersion)
                    axis.setMetaProperty(ReferenceAxisLoader.REF_CUBE_NAME, destCubeName)
                    axis.setMetaProperty(ReferenceAxisLoader.REF_AXIS_NAME, destAxisName)
                    ApplicationID appId = new ApplicationID(srcAppId.tenant, destApp, destVersion, ReleaseStatus.RELEASE.name(), ApplicationID.HEAD)

                    NCube target = getPersister().loadCube(appId, destCubeName)
                    if (target == null)
                    {
                        throw new IllegalArgumentException('Cannot point reference axis to non-existing cube (' +
                                destCubeName + '). Source: ' + srcAppId + ' ' + srcCubeName + '.' + srcAxisName +
                                ', target: ' + destApp + ' / ' + destVersion + ' / ' + destCubeName + '.' + destAxisName)
                    }

                    if (target.getAxis(destAxisName) == null)
                    {
                        throw new IllegalArgumentException('Cannot point reference axis to non-existing axis (' +
                                destAxisName + '). Source: ' + srcAppId + ' ' + srcCubeName + '.' + srcAxisName +
                                ', target: ' + destApp + ' / ' + destVersion + ' / ' + destCubeName + '.' + destAxisName)
                    }

                    axis.setMetaProperty(ReferenceAxisLoader.TRANSFORM_APP, transformApp)
                    axis.setMetaProperty(ReferenceAxisLoader.TRANSFORM_VERSION, transformVersion)
                    axis.setMetaProperty(ReferenceAxisLoader.TRANSFORM_CUBE_NAME, transformCubeName)
                    axis.setMetaProperty(ReferenceAxisLoader.TRANSFORM_METHOD_NAME, transformMethodName)

                    if (transformApp && transformVersion && transformCubeName && transformMethodName)
                    {   // If transformer cube reference supplied, verify that the cube exists
                        ApplicationID txAppId = new ApplicationID(srcAppId.tenant, transformApp, transformVersion, ReleaseStatus.RELEASE.name(), ApplicationID.HEAD)
                        NCube transformCube = getPersister().loadCube(txAppId, transformCubeName)
                        if (transformCube == null)
                        {
                            throw new IllegalArgumentException('Cannot point reference axis transformer to non-existing cube (' +
                                    transformCubeName + '). Source: ' + srcAppId + ' ' + srcCubeName + '.' + srcAxisName +
                                    ', target: ' + transformApp + ' / ' + transformVersion + ' / ' + transformCubeName + '.' + transformMethodName)
                        }

                        if (transformCube.getAxis('method') == null)
                        {
                            throw new IllegalArgumentException('Cannot point reference axis transformer to non-existing axis (' +
                                    transformMethodName + '). Source: ' + srcAppId + ' ' + srcCubeName + '.' + srcAxisName +
                                    ', target: ' + transformApp + ' / ' + transformVersion + ' / ' + transformCubeName + '.' + transformMethodName)
                        }
                    }

                    ncube.clearSha1()   // changing meta properties does not clear SHA-1 for recalculation.
                    getPersister().updateCube(axisRef.srcAppId, ncube, username)
                }
            }
        }
    }

    // ----------------------------------------- Resource APIs ---------------------------------------------------------
    static String getResourceAsString(String name) throws Exception
    {
        URL url = NCubeManager.class.getResource('/' + name)
        Path resPath = Paths.get(url.toURI())
        return new String(Files.readAllBytes(resPath), "UTF-8")
    }

    protected static NCube getNCubeFromResource(String name)
    {
        return getNCubeFromResource(ApplicationID.testAppId, name)
    }

    static NCube getNCubeFromResource(ApplicationID id, String name)
    {
        try
        {
            String json = getResourceAsString(name)
            NCube ncube = NCube.fromSimpleJson(json)
            ncube.setApplicationID(id)
            ncube.sha1()
            addCube(id, ncube)
            return ncube
        }
        catch (NullPointerException e)
        {
            throw new IllegalArgumentException('Could not find the file [n-cube]: ' + name + ', app: ' + id, e)
        }
        catch (Exception e)
        {
            if (e instanceof RuntimeException)
            {
                throw (RuntimeException)e
            }
            throw new RuntimeException('Failed to load cube from resource: ' + name, e)
        }
    }

    /**
     * Still used in getNCubesFromResource
     */
    private static Object[] getJsonObjectFromResource(String name) throws IOException
    {
        JsonReader reader = null
        try
        {
            URL url = NCubeManager.class.getResource('/' + name)
            File jsonFile = new File(url.getFile())
            InputStream input = new BufferedInputStream(new FileInputStream(jsonFile))
            reader = new JsonReader(input, true)
            return (Object[]) reader.readObject()
        }
        finally
        {
            IOUtilities.close(reader)
        }
    }

    static List<NCube> getNCubesFromResource(String name)
    {
        String lastSuccessful = ''
        try
        {
            Object[] cubes = getJsonObjectFromResource(name)
            List<NCube> cubeList = new ArrayList<>(cubes.length)

            for (Object cube : cubes)
            {
                JsonObject ncube = (JsonObject) cube
                String json = JsonWriter.objectToJson(ncube)
                NCube nCube = NCube.fromSimpleJson(json)
                nCube.sha1()
                addCube(nCube.getApplicationID(), nCube)
                lastSuccessful = nCube.getName()
                cubeList.add(nCube)
            }

            return cubeList
        }
        catch (Exception e)
        {
            String s = 'Failed to load cubes from resource: ' + name + ', last successful cube: ' + lastSuccessful
            LOG.warn(s)
            throw new RuntimeException(s, e)
        }
    }

    /**
     * Resolve the passed in String URL to a fully qualified URL object.  If the passed in String URL is relative
     * to a path in the sys.classpath, this method will perform (indirectly) the necessary HTTP HEAD requests to
     * determine which path it connects to.
     * @param url String url (relative or absolute)
     * @param input Map coordinate that the reuqested the URL (may include environment level settings that
     *              help sys.classpath select the correct ClassLoader.
     * @return URL fully qualified URL based on the passed in relative or absolute URL String.
     */
    static URL getActualUrl(ApplicationID appId, String url, Map input)
    {
        validateAppId(appId)
        if (StringUtilities.isEmpty(url))
        {
            throw new IllegalArgumentException('URL cannot be null or empty, attempting to resolve relative to absolute url for app: ' + appId)
        }
        String localUrl = url.toLowerCase()

        if (localUrl.startsWith('http:') || localUrl.startsWith('https:') || localUrl.startsWith('file:'))
        {   // Absolute URL
            try
            {
                return new URL(url)
            }
            catch (MalformedURLException e)
            {
                throw new IllegalArgumentException('URL is malformed: ' + url, e)
            }
        }
        else
        {
            URL actualUrl
            synchronized (url.intern())
            {
                URLClassLoader loader = getUrlClassLoader(appId, input)

                // Make URL absolute (uses URL roots added to NCubeManager)
                actualUrl = loader.getResource(url)
            }

            if (actualUrl == null)
            {
                String err = 'Unable to resolve URL, make sure appropriate resource URLs are added to the sys.classpath cube, URL: ' +
                        url + ', app: ' + appId
                throw new IllegalArgumentException(err)
            }
            return actualUrl
        }
    }

    // ---------------------------------------- Validation APIs --------------------------------------------------------
    protected static void validateAppId(ApplicationID appId)
    {
        if (appId == null)
        {
            throw new IllegalArgumentException('ApplicationID cannot be null')
        }
        appId.validate()
    }

    protected static void validateCube(NCube cube)
    {
        if (cube == null)
        {
            throw new IllegalArgumentException('NCube cannot be null')
        }
        NCube.validateCubeName(cube.getName())
    }

    // ---------------------- Broadcast APIs for notifying other services in cluster of cache changes ------------------
    protected static void broadcast(ApplicationID appId)
    {
        // Write to 'system' tenant, 'NCE' app, version '0.0.0', SNAPSHOT, cube: sys.cache
        // Separate thread reads from this table every 1 second, for new commands, for
        // example, clear cache
        appId.toString()
    }

    // --------------------------------------- Permissions -------------------------------------------------------------

    /**
     * Assert that the requested permission is allowed.  Throw a SecurityException if not.
     */
    static boolean assertPermissions(ApplicationID appId, String resource, ACTION action = ACTION.READ)
    {
        if (checkPermissions(appId, resource, action))
        {
            return true
        }
        throw new SecurityException('Operation not performed.  You do not have ' + action.name() + ' permission to ' + resource + ', app: ' + appId)
    }

    private static boolean assertNotLockBlocked(ApplicationID appId)
    {
        String lockedBy = getAppLockedBy(appId)
        if (lockedBy == null || lockedBy == getUserId())
        {
            return true
        }
        throw new SecurityException('Application is not locked by you, app: ' + appId)
    }

    private static void assertLockedByMe(ApplicationID appId)
    {
        final ApplicationID bootAppId = getBootAppId(appId)
        final NCube sysLockCube = getCubeInternal(bootAppId, SYS_LOCK)
        if (sysLockCube == null)
        {   // If there is no sys.lock cube, then no permissions / locking being used.
            if (isJUnitTest())
            {
                return
            }
            throw new SecurityException('Application is not locked by you, no sys.lock n-cube exists in app: ' + appId)
        }

        final String lockOwner = getAppLockedBy(bootAppId)
        if (getUserId() == lockOwner)
        {
            return
        }
        throw new SecurityException('Application is not locked by you, app: ' + appId)
    }

    private static ApplicationID getBootAppId(ApplicationID appId)
    {
        return new ApplicationID(appId.tenant, appId.app, '0.0.0', ReleaseStatus.SNAPSHOT.name(), ApplicationID.HEAD)
    }

    /**
     * Verify whether the action can be performed against the resource (typically cube name).
     * @param appId ApplicationID containing the n-cube being checked.
     * @param resource String cubeName or cubeName with wildcards('*' or '?') or cubeName / axisName (with wildcards).
     * @param action ACTION To be attempted.
     * @return boolean true if allowed, false if not.  If the permissions cubes restricting access have not yet been
     * added to the same App, then all access is granted.
     */
    static boolean checkPermissions(ApplicationID appId, String resource, ACTION action)
    {
        if (ACTION.READ == action && SYS_LOCK.equalsIgnoreCase(resource))
        {
            return true
        }

        ApplicationID bootVersion = getBootAppId(appId)
        NCube permCube = getCubeInternal(bootVersion, SYS_PERMISSIONS)
        if (permCube == null)
        {   // Allow everything if no permissions are set up.
            return true
        }

        NCube userToRole = getCubeInternal(bootVersion, SYS_USERGROUPS)
        if (userToRole == null)
        {   // Allow everything if no user roles are set up.
            return true
        }

        // Step 1: Get user's roles
        Set<String> roles = getRolesForUser(userToRole)

        if (!roles.contains(ROLE_ADMIN) && CUBE_MUTATE_ACTIONS.contains(action))
        {   // If user is not an admin, check branch permissions.
            NCube branchPermCube = getCubeInternal(bootVersion.asBranch(appId.branch), SYS_BRANCH_PERMISSIONS)
            if (branchPermCube != null && !checkBranchPermission(branchPermCube, resource))
            {
                return false
            }
        }

        // Step 2: Make sure one of the user's roles allows access
        final String actionName = action.lower()
        for (String role : roles)
        {
            if (checkResourcePermission(permCube, role, resource, actionName))
            {
                return true
            }
        }

        return false
    }

    /**
     * Faster permissions check that should be used when filtering a list of n-cubes.  Before calling this
     * API, call getPermInfo(AppId) to get the 'permInfo' Map to be used in this API.
     */
    static boolean fastCheckPermissions(String resource, ACTION action, Map permInfo)
    {
        if (ACTION.READ == action && SYS_LOCK.equalsIgnoreCase(resource))
        {
            return true
        }

        Set<String> roles = permInfo.roles as Set
        if (!roles.contains(ROLE_ADMIN) && CUBE_MUTATE_ACTIONS.contains(action))
        {   // If user is not an admin, check branch permissions.
            NCube branchPermCube = (NCube)permInfo.branchPermCube
            if (branchPermCube != null && !checkBranchPermission(branchPermCube, resource))
            {
                return false
            }
        }

        // Step 2: Make sure one of the user's roles allows access
        final String actionName = action.lower()
        NCube permCube = permInfo.permCube as NCube
        for (String role : roles)
        {
            if (checkResourcePermission(permCube, role, resource, actionName))
            {
                return true
            }
        }

        return false
    }

    private static Map getPermInfo(ApplicationID appId)
    {
        Map<String, Object> info = [skipPermCheck:false] as Map
        ApplicationID bootVersion = getBootAppId(appId)
        info.bootVersion = bootVersion
        NCube permCube = getCubeInternal(bootVersion, SYS_PERMISSIONS)
        if (permCube == null)
        {   // Allow everything if no permissions are set up.
            info.skipPermCheck = true
        }
        info.permCube = permCube

        NCube userToRole = getCubeInternal(bootVersion, SYS_USERGROUPS)
        if (userToRole == null)
        {   // Allow everything if no user roles are set up.
            info.skipPermCheck = true
        }
        else
        {
            info.roles = getRolesForUser(userToRole)
        }

        info.branch000 = bootVersion.asBranch(appId.branch)
        info.branchPermCube = getCubeInternal((ApplicationID)info.branch000, SYS_BRANCH_PERMISSIONS)
        return info
    }

    private static boolean checkBranchPermission(NCube branchPermissions, String resource)
    {
        final List<Column> resourceColumns = getResourcesToMatch(branchPermissions, resource)
        final String userId = getUserId()
        final Column column = resourceColumns.find { branchPermissions.getCell([resource: it.value, user: userId])}
        return column != null
    }

    private static boolean checkResourcePermission(NCube resourcePermissions, String role, String resource, String action)
    {
        final List<Column> resourceColumns = getResourcesToMatch(resourcePermissions, resource)
        final Column column = resourceColumns.find {resourcePermissions.getCell([(AXIS_ROLE): role, resource: it.value, action: action]) }
        return column != null
    }

    private static Set<String> getRolesForUser(NCube userGroups)
    {
        Axis role = userGroups.getAxis(AXIS_ROLE)
        Set<String> groups = new HashSet()
        for (Column column : role.columns)
        {
            if (userGroups.getCell([(AXIS_ROLE): column.value, (AXIS_USER): getUserId()]))
            {
                groups.add(column.value as String)
            }
        }
        return groups
    }

    private static List<Column> getResourcesToMatch(NCube permCube, String resource)
    {
        List<Column> matches = []
        Axis resourcePermissionAxis = permCube.getAxis(AXIS_RESOURCE)
        if (resource != null)
        {
            String[] splitResource = resource.split('/')
            boolean shouldCheckAxis = splitResource.length > 1
            String resourceCube = splitResource[0]
            String resourceAxis = shouldCheckAxis ? splitResource[1] : null

            for (Column resourcePermissionColumn : resourcePermissionAxis.getColumnsWithoutDefault())
            {
                String columnResource = resourcePermissionColumn.getValue()
                String[] curSplitResource = columnResource.split('/')
                boolean resourceIncludesAxis = curSplitResource.length > 1
                String curResourceCube = curSplitResource[0]
                String curResourceAxis = resourceIncludesAxis ? curSplitResource[1] : null
                boolean resourceMatchesCurrentResource = doStringsWithWildCardsMatch(resourceCube, curResourceCube)

                if ((shouldCheckAxis && resourceMatchesCurrentResource && doStringsWithWildCardsMatch(resourceAxis, curResourceAxis))
                        || (!shouldCheckAxis && !resourceIncludesAxis && resourceMatchesCurrentResource))
                {
                    matches << resourcePermissionColumn
                }
            }
        }
        if (matches.size() == 0)
        {
            matches << resourcePermissionAxis.getDefaultColumn()
        }
        return matches
    }

    private static boolean doStringsWithWildCardsMatch(String text, String pattern)
    {
        if (pattern == null)
        {
            return false
        }

        Pattern p = wildcards[pattern]
        if (p != null)
        {
            return p.matcher(text).matches()
        }

        String regexString = '(?i)' + StringUtilities.wildcardToRegexString(pattern)
        p = Pattern.compile(regexString)
        wildcards[pattern] = p
        return p.matcher(text).matches()
    }

    static boolean isAdmin(ApplicationID appId)
    {
        NCube userCube = getCubeInternal(getBootAppId(appId), SYS_USERGROUPS)
        if (userCube == null)
        {   // Allow everything if no permissions are set up.
            return true
        }
        return isUserInGroup(userCube, ROLE_ADMIN)
    }

    private static boolean isUserInGroup(NCube userCube, String groupName)
    {
        return userCube.getCell([(AXIS_ROLE): groupName, (AXIS_USER): null]) || userCube.getCell([(AXIS_ROLE): groupName, (AXIS_USER): getUserId()])
    }

    protected static void detectNewAppId(ApplicationID appId)
    {
        if (search(appId, null, null, [(SEARCH_ACTIVE_RECORDS_ONLY):false]).size() == 0)
        {
            addAppPermissionsCubes(appId)
            addBranchPermissionsCube(appId)
        }
    }

    private static void addBranchPermissionsCube(ApplicationID appId)
    {
        ApplicationID permAppId = appId.asVersion('0.0.0')
        if (getCubeInternal(permAppId, SYS_BRANCH_PERMISSIONS) != null)
        {
            return
        }

        String userId = getUserId()
        NCube branchPermCube = new NCube(SYS_BRANCH_PERMISSIONS)
        branchPermCube.setApplicationID(permAppId)
        branchPermCube.setDefaultCellValue(false)

        Axis resourceAxis = new Axis(AXIS_RESOURCE, AxisType.DISCRETE, AxisValueType.STRING, true)
        resourceAxis.addColumn(SYS_BRANCH_PERMISSIONS)
        branchPermCube.addAxis(resourceAxis)

        Axis userAxis = new Axis(AXIS_USER, AxisType.DISCRETE, AxisValueType.STRING, true)
        userAxis.addColumn(userId)
        branchPermCube.addAxis(userAxis)

        branchPermCube.setCell(true, [(AXIS_USER):userId, (AXIS_RESOURCE):SYS_BRANCH_PERMISSIONS])
        branchPermCube.setCell(true, [(AXIS_USER):userId, (AXIS_RESOURCE):null])

        getPersister().updateCube(permAppId, branchPermCube, userId)
        updateBranch(permAppId, userId)
    }

    private static void addAppPermissionsCubes(ApplicationID appId)
    {
        ApplicationID permAppId = getBootAppId(appId)
        addAppUserGroupsCube(permAppId)
        addAppPermissionsCube(permAppId)
        addSysLockingCube(permAppId)
    }

    private static void addSysLockingCube(ApplicationID appId)
    {
        if (getCubeInternal(appId, SYS_LOCK) != null) {
            return
        }

        NCube sysLockCube = new NCube(SYS_LOCK)
        sysLockCube.setApplicationID(appId)
        sysLockCube.setMetaProperty(PROPERTY_CACHE, false)
        sysLockCube.addAxis(new Axis(AXIS_SYSTEM, AxisType.DISCRETE, AxisValueType.STRING, true))
        getPersister().updateCube(appId, sysLockCube, getUserId())
    }

    /**
     * Determine if the ApplicationID is locked.  This is an expensive call because it
     * always hits the database.  Use judiciously (obtain value before loops, etc.)
     */
    static String getAppLockedBy(ApplicationID appId)
    {
        NCube sysLockCube = getCubeInternal(getBootAppId(appId), SYS_LOCK)
        if (sysLockCube == null)
        {
            return null
        }
        return sysLockCube.getCell([(AXIS_SYSTEM):null])
    }

    /**
     * Lock the given appId so that no changes can be made to any cubes within it
     * @param appId ApplicationID to lock
     */
    static boolean lockApp(ApplicationID appId)
    {
        String userId = getUserId()
        ApplicationID bootAppId = getBootAppId(appId)

        String lockOwner = getAppLockedBy(appId)
        if (userId == lockOwner)
        {
            return false
        }
        if (lockOwner != null)
        {
            throw new SecurityException('Application ' + appId + ' already locked by ' + lockOwner)
        }

        NCube sysLockCube = getCubeInternal(bootAppId, SYS_LOCK)
        if (sysLockCube == null)
        {
            return false
        }
        sysLockCube.setCell(userId, [(AXIS_SYSTEM):null])
        getPersister().updateCube(bootAppId, sysLockCube, userId)
        return true
    }

    /**
     * Unlock the given appId so that changes can be made to any cubes within it
     * @param appId ApplicationID to unlock
     */
    static void unlockApp(ApplicationID appId)
    {
        ApplicationID bootAppId = getBootAppId(appId)
        NCube sysLockCube = getCubeInternal(bootAppId, SYS_LOCK)
        if (sysLockCube == null)
        {
            return
        }

        String userId = getUserId()
        String lockOwner = getAppLockedBy(appId)
        if (userId != lockOwner)
        {
            throw new SecurityException('Application ' + appId + ' locked by ' + lockOwner)
        }

        sysLockCube.removeCell([(AXIS_SYSTEM):null])
        getPersister().updateCube(bootAppId, sysLockCube, getUserId())
    }

    private static void addAppUserGroupsCube(ApplicationID appId)
    {
        if (getCubeInternal(appId, SYS_USERGROUPS) != null) {
            return
        }

        String userId = getUserId()
        NCube userGroupsCube = new NCube(SYS_USERGROUPS)
        userGroupsCube.setApplicationID(appId)
        userGroupsCube.setDefaultCellValue(false)

        Axis userAxis = new Axis(AXIS_USER, AxisType.DISCRETE, AxisValueType.STRING, true)
        userAxis.addColumn(userId)
        userGroupsCube.addAxis(userAxis)

        Axis roleAxis = new Axis(AXIS_ROLE, AxisType.DISCRETE, AxisValueType.STRING, false)
        roleAxis.addColumn(ROLE_ADMIN)
        roleAxis.addColumn(ROLE_READONLY)
        roleAxis.addColumn(ROLE_USER)
        userGroupsCube.addAxis(roleAxis)

        userGroupsCube.setCell(true, [(AXIS_USER):userId, (AXIS_ROLE):ROLE_ADMIN])
        userGroupsCube.setCell(true, [(AXIS_USER):userId, (AXIS_ROLE):ROLE_USER])
        userGroupsCube.setCell(true, [(AXIS_USER):null, (AXIS_ROLE):ROLE_USER])

        getPersister().updateCube(appId, userGroupsCube, userId)
    }

    private static void addAppPermissionsCube(ApplicationID appId)
    {
        if (getCubeInternal(appId, SYS_PERMISSIONS)) {
            return
        }

        NCube appPermCube = new NCube(SYS_PERMISSIONS)
        appPermCube.setApplicationID(appId)
        appPermCube.setDefaultCellValue(false)

        Axis resourceAxis = new Axis(AXIS_RESOURCE, AxisType.DISCRETE, AxisValueType.STRING, true)
        resourceAxis.addColumn(SYS_PERMISSIONS)
        resourceAxis.addColumn(SYS_USERGROUPS)
        resourceAxis.addColumn(SYS_BRANCH_PERMISSIONS)
        resourceAxis.addColumn(SYS_LOCK)
        appPermCube.addAxis(resourceAxis)

        Axis roleAxis = new Axis(AXIS_ROLE, AxisType.DISCRETE, AxisValueType.STRING, false)
        roleAxis.addColumn(ROLE_ADMIN)
        roleAxis.addColumn(ROLE_READONLY)
        roleAxis.addColumn(ROLE_USER)
        appPermCube.addAxis(roleAxis)

        Axis actionAxis = new Axis(AXIS_ACTION, AxisType.DISCRETE, AxisValueType.STRING, false)
        actionAxis.addColumn(ACTION.UPDATE.lower())
        actionAxis.addColumn(ACTION.READ.lower())
        actionAxis.addColumn(ACTION.RELEASE.lower())
        actionAxis.addColumn(ACTION.COMMIT.lower())
        appPermCube.addAxis(actionAxis)

        appPermCube.setCell(true, [(AXIS_RESOURCE):SYS_PERMISSIONS, (AXIS_ROLE):ROLE_ADMIN, (AXIS_ACTION):ACTION.UPDATE.lower()])
        appPermCube.setCell(true, [(AXIS_RESOURCE):SYS_PERMISSIONS, (AXIS_ROLE):ROLE_ADMIN, (AXIS_ACTION):ACTION.READ.lower()])
        appPermCube.setCell(true, [(AXIS_RESOURCE):SYS_PERMISSIONS, (AXIS_ROLE):ROLE_ADMIN, (AXIS_ACTION):ACTION.COMMIT.lower()])

        appPermCube.setCell(true, [(AXIS_RESOURCE):SYS_USERGROUPS, (AXIS_ROLE):ROLE_ADMIN, (AXIS_ACTION):ACTION.UPDATE.lower()])
        appPermCube.setCell(true, [(AXIS_RESOURCE):SYS_USERGROUPS, (AXIS_ROLE):ROLE_ADMIN, (AXIS_ACTION):ACTION.READ.lower()])
        appPermCube.setCell(true, [(AXIS_RESOURCE):SYS_USERGROUPS, (AXIS_ROLE):ROLE_ADMIN, (AXIS_ACTION):ACTION.COMMIT.lower()])
        appPermCube.setCell(true, [(AXIS_RESOURCE):SYS_USERGROUPS, (AXIS_ROLE):ROLE_USER, (AXIS_ACTION):ACTION.READ.lower()])

        appPermCube.setCell(true, [(AXIS_RESOURCE):SYS_BRANCH_PERMISSIONS, (AXIS_ROLE):ROLE_ADMIN, (AXIS_ACTION):ACTION.UPDATE.lower()])
        appPermCube.setCell(true, [(AXIS_RESOURCE):SYS_BRANCH_PERMISSIONS, (AXIS_ROLE):ROLE_ADMIN, (AXIS_ACTION):ACTION.READ.lower()])
        appPermCube.setCell(true, [(AXIS_RESOURCE):SYS_BRANCH_PERMISSIONS, (AXIS_ROLE):ROLE_USER, (AXIS_ACTION):ACTION.UPDATE.lower()])
        appPermCube.setCell(true, [(AXIS_RESOURCE):SYS_BRANCH_PERMISSIONS, (AXIS_ROLE):ROLE_USER, (AXIS_ACTION):ACTION.READ.lower()])

        appPermCube.setCell(true, [(AXIS_RESOURCE):SYS_LOCK, (AXIS_ROLE):ROLE_ADMIN, (AXIS_ACTION):ACTION.UPDATE.lower()])
        appPermCube.setCell(true, [(AXIS_RESOURCE):SYS_LOCK, (AXIS_ROLE):ROLE_ADMIN, (AXIS_ACTION):ACTION.READ.lower()])
        appPermCube.setCell(true, [(AXIS_RESOURCE):SYS_LOCK, (AXIS_ROLE):ROLE_ADMIN, (AXIS_ACTION):ACTION.COMMIT.lower()])

        appPermCube.setCell(true, [(AXIS_RESOURCE):null, (AXIS_ROLE):ROLE_ADMIN, (AXIS_ACTION):ACTION.UPDATE.lower()])
        appPermCube.setCell(true, [(AXIS_RESOURCE):null, (AXIS_ROLE):ROLE_ADMIN, (AXIS_ACTION):ACTION.READ.lower()])
        appPermCube.setCell(true, [(AXIS_RESOURCE):null, (AXIS_ROLE):ROLE_ADMIN, (AXIS_ACTION):ACTION.RELEASE.lower()])
        appPermCube.setCell(true, [(AXIS_RESOURCE):null, (AXIS_ROLE):ROLE_ADMIN, (AXIS_ACTION):ACTION.COMMIT.lower()])
        appPermCube.setCell(true, [(AXIS_RESOURCE):null, (AXIS_ROLE):ROLE_USER, (AXIS_ACTION):ACTION.UPDATE.lower()])
        appPermCube.setCell(true, [(AXIS_RESOURCE):null, (AXIS_ROLE):ROLE_USER, (AXIS_ACTION):ACTION.READ.lower()])
        appPermCube.setCell(true, [(AXIS_RESOURCE):null, (AXIS_ROLE):ROLE_USER, (AXIS_ACTION):ACTION.COMMIT.lower()])
        appPermCube.setCell(true, [(AXIS_RESOURCE):null, (AXIS_ROLE):ROLE_READONLY, (AXIS_ACTION):ACTION.READ.lower()])

        getPersister().updateCube(appId, appPermCube, getUserId())
    }

    /**
     * Testing API (Cache validation)
     */
    static boolean isCubeCached(ApplicationID appId, String cubeName)
    {
        validateAppId(appId)
        NCube.validateCubeName(cubeName)
        Map<String, Object> ncubes = getCacheForApp(appId)
        Object cachedItem = ncubes[cubeName.toLowerCase()]
        return cachedItem instanceof NCube
    }

    private static void cacheCube(ApplicationID appId, NCube ncube)
    {
        if (!ncube.getMetaProperties().containsKey(PROPERTY_CACHE) || Boolean.TRUE.equals(ncube.getMetaProperty(PROPERTY_CACHE)))
        {
            Map<String, Object> cache = getCacheForApp(appId)
            cache[ncube.name.toLowerCase()] = ncube
        }
    }

    private static void removeCachedCube(ApplicationID appId, String cubeName)
    {
        if (StringUtilities.isEmpty(cubeName))
        {
            return
        }
        Map<String, Object> cache = getCacheForApp(appId)
        cache.remove(cubeName.toLowerCase())
    }

    /**
     * Set the user ID on the current thread
     * @param user String user Id
     */
    static void setUserId(String user)
    {
        userId.set(user?.trim())
    }

    /**
     * Retrieve the user ID from the current thread
     * @return String user ID of the user associated to the requesting thread
     */
    static String getUserId()
    {
        return userId.get()
    }
}
