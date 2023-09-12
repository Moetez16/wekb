package wekb


import wekb.tools.UrlToolkit
import wekb.helper.RDStore
import grails.gorm.transactions.Transactional
import org.apache.commons.lang.StringUtils
import wekb.system.JobResult

import java.time.LocalTime
import java.util.concurrent.ExecutorService

import groovyx.gpars.GParsPool
import java.util.concurrent.Future

@Transactional
class AutoUpdatePackagesService {

    static final THREAD_POOL_SIZE = 5
    public static boolean running = false;
    Map result = [result: JobResult.STATUS_SUCCESS]
    ExportService exportService
    ExecutorService executorService
    Future activeFuture
    FtpConnectService ftpConnectService

    KbartProcessService kbartProcessService

    void findPackageToUpdateAndUpdate(boolean onlyRowsWithLastChanged = false) {
        List packageNeedsUpdate = []
        def updPacks = Package.executeQuery(
                "from Package p " +
                        "where p.kbartSource is not null and " +
                        "p.kbartSource.automaticUpdates = true " +
                        "and (p.kbartSource.lastRun is null or p.kbartSource.lastRun < current_date) order by p.kbartSource.lastRun")
        updPacks.each { Package p ->
            if (p.kbartSource.needsUpdate()) {
                packageNeedsUpdate << p
            }
        }
        log.info("findPackageToUpdateAndUpdate: Package with KbartSource and lastRun < currentDate (${packageNeedsUpdate.size()})")
        if (packageNeedsUpdate.size() > 0) {
            /*  packageNeedsUpdate.eachWithIndex { Package aPackage, int idx ->
                  while(!(activeFuture) || activeFuture.isDone() || idx == 0) {
                      activeFuture = executorService.submit({
                          Package pkg = Package.get(aPackage.id)
                          Thread.currentThread().setName('startAutoPackageUpdate' + aPackage.id)
                          startAutoPackageUpdate(pkg, onlyRowsWithLastChanged)
                      })
                      println("Wait")
                  }
                  println("Test:"+aPackage.name)
              }*/
            GParsPool.withPool(THREAD_POOL_SIZE) { pool ->
                packageNeedsUpdate.anyParallel { aPackage ->
                    startAutoPackageUpdate(aPackage, onlyRowsWithLastChanged)
                }
            }
        }

    }


    static List<URL> getUpdateUrls(String url, Date lastProcessingDate, Date packageCreationDate) {
        if (lastProcessingDate == null) {
            lastProcessingDate = packageCreationDate
        }
        if (StringUtils.isEmpty(url) || lastProcessingDate == null) {
            return new ArrayList<URL>()
        }
        if (UrlToolkit.containsDateStamp(url) || UrlToolkit.containsDateStampPlaceholder(url)) {
            return UrlToolkit.getUpdateUrlList(url, lastProcessingDate.toString())
        } else {
            return Arrays.asList(new URL(url))
        }
    }

    void startAutoPackageUpdate(Package pkg, boolean onlyRowsWithLastChanged = false) {
        log.info("Begin startAutoPackageUpdate Package ($pkg.name)")
        List kbartRows = []
        String lastUpdateURL = ""
        Date startTime = new Date()
        if (pkg.status in [RDStore.KBC_STATUS_REMOVED, RDStore.KBC_STATUS_DELETED]) {
            UpdatePackageInfo updatePackageInfo = new UpdatePackageInfo(pkg: pkg, startTime: startTime, endTime: new Date(), status: RDStore.UPDATE_STATUS_SUCCESSFUL, description: "Package status is ${pkg.status.value}. Update for this package is not starting.", onlyRowsWithLastChanged: onlyRowsWithLastChanged, automaticUpdate: true, kbartHasWekbFields: false)
            updatePackageInfo.save()
        } else {
            UpdatePackageInfo updatePackageInfo = new UpdatePackageInfo(pkg: pkg, startTime: startTime, status: RDStore.UPDATE_STATUS_SUCCESSFUL, description: "Starting Update package.", onlyRowsWithLastChanged: onlyRowsWithLastChanged, automaticUpdate: true).save()
            try {
                if (pkg.kbartSource) {
                    if (pkg.kbartSource.defaultSupplyMethod == RDStore.KS_DSMETHOD_FTP) {
                        updatePackageInfo.updateFromFTP = true
                        updatePackageInfo.save()
                        if (pkg.kbartSource.ftpServerUrl) {
                            File file = ftpConnectService.ftpConnectAndGetFile(pkg.kbartSource, updatePackageInfo)


                            if (file) {
                                kbartRows = kbartProcessService.kbartProcess(file, "", updatePackageInfo)
                            } else {
                                UpdatePackageInfo.withTransaction {
                                    updatePackageInfo.description = "No KBART File found by FTP Server!"
                                    updatePackageInfo.status = RDStore.UPDATE_STATUS_FAILED
                                    updatePackageInfo.endTime = new Date()
                                    updatePackageInfo.save()
                                }
                            }

                            if (kbartRows.size() > 0) {
                                updatePackageInfo = kbartProcessService.kbartImportProcess(kbartRows, pkg, "", updatePackageInfo, onlyRowsWithLastChanged)
                            }

                        } else {
                            UpdatePackageInfo.withTransaction {
                                //UpdatePackageInfo updatePackageFail = new UpdatePackageInfo()
                                updatePackageInfo.description = "No FTP server url define in the source of the package."
                                updatePackageInfo.status = RDStore.UPDATE_STATUS_FAILED
                                updatePackageInfo.startTime = startTime
                                updatePackageInfo.endTime = new Date()
                                updatePackageInfo.pkg = pkg
                                updatePackageInfo.onlyRowsWithLastChanged = onlyRowsWithLastChanged
                                updatePackageInfo.automaticUpdate = true
                                updatePackageInfo.save()
                            }
                        }
                    }else if (pkg.kbartSource.defaultSupplyMethod == RDStore.KS_DSMETHOD_HTTP_URL) {
                        updatePackageInfo.updateFromURL = true
                        updatePackageInfo.save()
                        if (pkg.kbartSource.url) {
                            List<URL> updateUrls
                            if (pkg.getTippCount() <= 0 || pkg.kbartSource.lastRun == null) {
                                updateUrls = new ArrayList<>()
                                updateUrls.add(new URL(pkg.kbartSource.url))
                            } else {
                                // this package had already been filled with data
                                if ((UrlToolkit.containsDateStamp(pkg.kbartSource.url) || UrlToolkit.containsDateStampPlaceholder(pkg.kbartSource.url)) && pkg.kbartSource.lastUpdateUrl) {
                                    updateUrls = getUpdateUrls(pkg.kbartSource.lastUpdateUrl, pkg.kbartSource.lastRun, pkg.dateCreated)
                                } else {
                                    updateUrls = getUpdateUrls(pkg.kbartSource.url, pkg.kbartSource.lastRun, pkg.dateCreated)
                                }
                            }
                            log.info("Got ${updateUrls}")
                            Iterator urlsIterator = updateUrls.listIterator(updateUrls.size())

                            File file
                            if (updateUrls.size() > 0) {
                                LocalTime kbartFromUrlStartTime = LocalTime.now()
                                while (urlsIterator.hasPrevious()) {
                                    URL url = urlsIterator.previous()
                                    lastUpdateURL = url.toString()
                                    try {
                                        file = exportService.kbartFromUrl(lastUpdateURL)

                                        //if (kbartFromUrlStartTime < LocalTime.now().minus(45, ChronoUnit.MINUTES)){ sense???
                                        //break
                                        //}

                                    }
                                    catch (Exception e) {
                                        log.error("get kbartFromUrl: ${e}")
                                        continue
                                    }

                                }

                                if (file) {
                                    kbartRows = kbartProcessService.kbartProcess(file, lastUpdateURL, updatePackageInfo)
                                } else {
                                    UpdatePackageInfo.withTransaction {
                                        updatePackageInfo.description = "No KBART File found by URL: ${lastUpdateURL}!"
                                        updatePackageInfo.status = RDStore.UPDATE_STATUS_FAILED
                                        updatePackageInfo.endTime = new Date()
                                        updatePackageInfo.updateUrl = lastUpdateURL
                                        updatePackageInfo.save()
                                    }
                                }

                            }

                            if (kbartRows.size() > 0) {
                                updatePackageInfo = kbartProcessService.kbartImportProcess(kbartRows, pkg, lastUpdateURL, updatePackageInfo, onlyRowsWithLastChanged)
                            }
                        } else {
                            UpdatePackageInfo.withTransaction {
                                //UpdatePackageInfo updatePackageFail = new UpdatePackageInfo()
                                updatePackageInfo.description = "No url define in the source of the package."
                                updatePackageInfo.status = RDStore.UPDATE_STATUS_FAILED
                                updatePackageInfo.startTime = startTime
                                updatePackageInfo.endTime = new Date()
                                updatePackageInfo.pkg = pkg
                                updatePackageInfo.onlyRowsWithLastChanged = onlyRowsWithLastChanged
                                updatePackageInfo.automaticUpdate = true
                                updatePackageInfo.save()
                            }
                        }
                    }else {
                        UpdatePackageInfo.withTransaction {
                            //UpdatePackageInfo updatePackageFail = new UpdatePackageInfo()
                            updatePackageInfo.description = "Default Supply Method not set in source! Please set Default Supply Method!"
                            updatePackageInfo.status = RDStore.UPDATE_STATUS_FAILED
                            updatePackageInfo.startTime = startTime
                            updatePackageInfo.endTime = new Date()
                            updatePackageInfo.pkg = pkg
                            updatePackageInfo.onlyRowsWithLastChanged = onlyRowsWithLastChanged
                            updatePackageInfo.automaticUpdate = true
                            updatePackageInfo.save()
                        }
                    }
                }

            } catch (Exception exception) {
                log.error("Error by startAutoPackageUapdate: ${exception.message}" + exception.printStackTrace())
                UpdatePackageInfo.withTransaction {
                    //UpdatePackageInfo updatePackageFail = new UpdatePackageInfo()
                    updatePackageInfo.description = "An error occurred while processing the KBART file. More information can be seen in the system log. File from URL: ${lastUpdateURL}"
                    updatePackageInfo.status = RDStore.UPDATE_STATUS_FAILED
                    updatePackageInfo.startTime = startTime
                    updatePackageInfo.endTime = new Date()
                    updatePackageInfo.pkg = pkg
                    updatePackageInfo.onlyRowsWithLastChanged = onlyRowsWithLastChanged
                    updatePackageInfo.automaticUpdate = true
                    updatePackageInfo.save()
                }
            }
        }
        log.info("End startAutoPackageUpdate Package ($pkg.name)")
    }


}
