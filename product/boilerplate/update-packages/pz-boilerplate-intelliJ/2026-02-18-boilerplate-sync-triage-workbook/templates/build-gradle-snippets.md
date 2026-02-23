# build.gradle snippets

## 1) Ensure import exists
```groovy
import java.util.Locale
```

## 2) Add task
```groovy
tasks.register('boilerplateSyncWorkbook', Exec) {
    group = 'documentation'
    description = 'Builds management/docs/boilerplate-sync-candidates.xlsx from product/boilerplate/update-packages/pz-boilerplate-intelliJ while preserving manual decision columns.'
    dependsOn(':afp-tools:classes')
    onlyIf {
        File packagesDir = file('product/boilerplate/update-packages/pz-boilerplate-intelliJ')
        File xlsx = file('management/docs/boilerplate-sync-candidates.xlsx')
        String forceRaw = (System.getenv('AFP_FORCE_BOILERPLATE_SYNC_UPDATE') ?: '').trim().toLowerCase(Locale.ROOT)
        boolean force = forceRaw == '1' || forceRaw == 'true' || forceRaw == 'yes'
        if (force) {
            logger.lifecycle("boilerplateSyncWorkbook: running because AFP_FORCE_BOILERPLATE_SYNC_UPDATE is set")
            return true
        }
        if (!packagesDir.exists() || !packagesDir.isDirectory()) {
            logger.lifecycle("boilerplateSyncWorkbook: skipping because packages directory is missing")
            return false
        }
        if (!xlsx.exists()) {
            logger.lifecycle("boilerplateSyncWorkbook: running because workbook is missing")
            return true
        }
        long newestPackageInput = 0L
        Set<File> inputs = fileTree(packagesDir) { include '**/*' }.files
        for (File f : inputs) {
            newestPackageInput = Math.max(newestPackageInput, f.lastModified())
        }
        newestPackageInput = Math.max(newestPackageInput, packagesDir.lastModified())
        boolean shouldRun = newestPackageInput > xlsx.lastModified()
        logger.lifecycle(
            "boilerplateSyncWorkbook: " + (shouldRun ? "running" : "skipping")
                + " (packagesLastModified=" + newestPackageInput
                + ", xlsxLastModified=" + xlsx.lastModified() + ")"
        )
        return shouldRun
    }
    doFirst {
        def sourceSets = project(':afp-tools').sourceSets
        String cp = sourceSets.main.runtimeClasspath.asPath
        commandLine 'java',
            '-cp', cp,
            'solutions.pointzero.symphony.afp.tools.BoilerplateSyncWorkbookUpdater',
            '--packages', 'product/boilerplate/update-packages/pz-boilerplate-intelliJ',
            '--xlsx', 'management/docs/boilerplate-sync-candidates.xlsx'
    }
}
```

## 3) Wire task into manifest pipeline
- Add dependency in `documentationManifest`:
```groovy
dependsOn('boilerplateSyncWorkbook')
```

## 4) Track workbook in management/docs/artifacts
- Add file entries where docs xlsx artifacts are listed:
```groovy
file('management/docs/boilerplate-sync-candidates.xlsx')
```
