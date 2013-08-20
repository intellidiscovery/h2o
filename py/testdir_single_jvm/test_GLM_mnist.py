import unittest
import random, sys, time, re
sys.path.extend(['.','..','py'])

import h2o, h2o_cmd, h2o_hosts, h2o_browse as h2b, h2o_import as h2i, h2o_glm, h2o_util
class Basic(unittest.TestCase):
    def tearDown(self):
        h2o.check_sandbox_for_errors()

    @classmethod
    def setUpClass(cls):
        # assume we're at 0xdata with it's hdfs namenode
        global localhost
        localhost = h2o.decide_if_localhost()
        if (localhost):
            h2o.build_cloud(1)
        else:
            # all hdfs info is done thru the hdfs_config michal's ec2 config sets up?
            h2o_hosts.build_cloud_with_hosts(1, 
                # this is for our amazon ec hdfs
                # see https://github.com/0xdata/h2o/wiki/H2O-and-s3n
                hdfs_name_node='10.78.14.235:9000',
                hdfs_version='0.20.2')

    @classmethod
    def tearDownClass(cls):
        h2o.tear_down_cloud()

    def test_GLM_mnist(self):
        importFolderPath = "/home/0xdiag/datasets/mnist"
        csvFilelist = [
            ("mnist_training.csv.gz", "mnist_testing.csv.gz",    600), 
        ]
        # IMPORT**********************************************
        # since H2O deletes the source key, we should re-import every iteration if we re-use the src in the list
        importFolderResult = h2i.setupImportFolder(None, importFolderPath)
        ### print "importHDFSResult:", h2o.dump_json(importFolderResult)
        if 'files' in importFolderResult:
            succeededList = importFolderResult['files']
        else:
            succeededList = importFolderResult['succeeded']
        ### print "succeededList:", h2o.dump_json(succeededList)

        self.assertGreater(len(succeededList),1,"Should see more than 1 files in the import?")
        # why does this hang? can't look at storeview after import?
        print "\nTrying StoreView after the import folder"
        h2o_cmd.runStoreView(timeoutSecs=30)

        trial = 0
        for (trainCsvFilename, testCsvFilename, timeoutSecs) in csvFilelist:
            trialStart = time.time()

            # PARSE test****************************************
            testKey2 = testCsvFilename + "_" + str(trial) + ".hex"
            start = time.time()
            parseKey = h2i.parseImportFolderFile(None, testCsvFilename, importFolderPath,
                key2=testKey2, timeoutSecs=timeoutSecs)
            elapsed = time.time() - start
            print "parse end on ", testCsvFilename, 'took', elapsed, 'seconds',\
                "%d pct. of timeout" % ((elapsed*100)/timeoutSecs)
            print "parse result:", parseKey['destination_key']

            print "We won't use this pruning of x on test data. See if it prunes the same as the training"
            y = 0 # first column is pixel value
            print "y:"
            x = h2o_glm.goodXFromColumnInfo(y, key=parseKey['destination_key'], timeoutSecs=300)

            # PARSE train****************************************
            trainKey2 = trainCsvFilename + "_" + str(trial) + ".hex"
            start = time.time()
            parseKey = h2i.parseImportFolderFile(None, trainCsvFilename, importFolderPath,
                key2=trainKey2, timeoutSecs=timeoutSecs, noise=('StoreView', None))

            elapsed = time.time() - start
            print "parse end on ", trainCsvFilename, 'took', elapsed, 'seconds',\
                "%d pct. of timeout" % ((elapsed*100)/timeoutSecs)
            print "parse result:", parseKey['destination_key']

            # GLM****************************************
            print "This is the pruned x we'll use"
            x = h2o_glm.goodXFromColumnInfo(y, key=parseKey['destination_key'], timeoutSecs=300)
            print "x:", x

            params = {
                'x': x, 
                'y': y,
                'case_mode': '=',
                'case': 0,
                'family': 'binomial',
                'lambda': 1.0E-5,
                'alpha': 0.0,
                'max_iter': 5,
                'thresholds': 0.5,
                'n_folds': 1,
                'weight': 1,
                'beta_epsilon': 1.0E-4,
                }

            for c in [0,1,2,3,4,5,6,7,8,9]:
                kwargs = params.copy()
                print "Trying binomial with case:", c
                kwargs['case'] = c

                timeoutSecs = 1800
                start = time.time()
                glm = h2o_cmd.runGLMOnly(parseKey=parseKey, 
                    timeoutSecs=timeoutSecs, pollTimeoutsecs=60, noise=('StoreView', None), **kwargs)
                elapsed = time.time() - start
                print "GLM completed in", elapsed, "seconds.", \
                    "%d pct. of timeout" % ((elapsed*100)/timeoutSecs)

                h2o_glm.simpleCheckGLM(self, glm, None, noPrint=True, **kwargs)
                GLMModel = glm['GLMModel']
                modelKey = GLMModel['model_key']

                start = time.time()
                glmScore = h2o_cmd.runGLMScore(key=testKey2, model_key=modelKey, thresholds="0.5",
                    timeoutSecs=60)
                elapsed = time.time() - start
                print "GLMScore in",  elapsed, "secs", \
                    "%d pct. of timeout" % ((elapsed*100)/timeoutSecs)
                h2o_glm.simpleCheckGLMScore(self, glmScore, **kwargs)

if __name__ == '__main__':
    h2o.unit_main()
