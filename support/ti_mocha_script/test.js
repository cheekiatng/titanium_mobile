/**
 * Copyright (c) 2015 by Appcelerator, Inc. All Rights Reserved.
 * Licensed under the terms of the Apache Public License.
 * Please see the LICENSE included with this distribution for details.
 */
var path = require('path'),
	fs = require('fs'),
	async = require('async'),
	colors = require('colors'),
	wrench = require('wrench'),
	ejs = require('ejs'),
	spawn = require('child_process').spawn,
	exec = require('child_process').exec,
	sdkPath,
	iosTestResults,
	androidTestResults,
	jsonResults;

function installSDK(next) {
	var prc = spawn('titanium', ['sdk', 'install', '-b', 'master', '-d']);
	prc.stdout.on('data', function (data) {
	   console.log(data.toString().trim());
	});
	prc.stderr.on('data', function (data) {
	   console.error(data.toString().trim());
	});

	prc.on('close', function (code) {
		var setProcess;
		if (code != 0) {
			next("Failed to install master SDK. Exit code: " + code);
		} else {
			next();
		}
	});
}

function getSDKInstallDir(next) {
	var prc = exec('titanium info -o json -t titanium', function (error, stdout, stderr) {
		var out,
			selectedSDK;
		if (error !== null) {
		  next('Failed to get SDK install dir: ' + error);
		}

		out = JSON.parse(stdout);
		selectedSDK = out['titaniumCLI']['selectedSDK'];

		sdkPath = out['titanium'][selectedSDK]['path'];
		next();
	});
}

function generateProject(next) {
	var projectDir = path.join(__dirname, 'mocha'),
		prc;
	// If the project already exists, wipe it
	if (fs.existsSync(projectDir)) {
		wrench.rmdirSyncRecursive(projectDir);
	}
	prc = spawn('titanium', ['create', '--force', '--type', 'app', '--platforms', 'android,ios', '--name', 'mocha', '--id', 'com.appcelerator.mocha.testing', '--url', 'http://www.appcelerator.com', '--workspace-dir', __dirname, '--no-prompt']);
	prc.stdout.on('data', function (data) {
		console.log(data.toString());
	});
	prc.stderr.on('data', function (data) {
		console.log(data.toString());
	});
	prc.on('close', function (code) {
		var setProcess;
		if (code != 0) {
			next("Failed to create project");
		} else {
			next();
		}
	});
}

// Add required properties for our unit tests!
function addTiAppProperties(next) {
	var tiapp_xml = path.join(__dirname, 'mocha', 'tiapp.xml');

	// Not so smart but this should work...
	var content = [];
	fs.readFileSync(tiapp_xml).toString().split(/\r?\n/).forEach(function(line) {
		content.push(line);
		if (line.indexOf('<guid>') >= 0) {
			//content.push('\t<property name="presetString" type="string">Hello!</property>');
			//content.push('\t<property name="presetBool" type="bool">true</property>');
			//content.push('\t<property name="presetInt" type="int">1337</property>');
			//content.push('\t<property name="presetDouble" type="double">1.23456</property>');
		}
	});
	fs.writeFileSync(tiapp_xml, content.join('\n'));

	next();
}

function copyMochaAssets(next) {
	var mochaAssetsDir = path.join(__dirname, '..', '..', 'ti_mocha_tests'),
		dest = path.join(__dirname, 'mocha', 'Resources');
	wrench.copyDirSyncRecursive(mochaAssetsDir, dest, {
		forceDelete: true
	});
	next();
}

function runIOSBuild(next, count) {
	var prc,
		inResults = false,
		done = false;
	prc = spawn('titanium', ['build', '--project-dir', path.join(__dirname, 'mocha'), '--platform', 'ios', '--target', 'simulator', '--no-prompt', '--no-colors']);
	prc.stdout.on('data', function (data) {
		console.log(data.toString());
		var lines = data.toString().trim().match(/^.*([\n\r]+|$)/gm);
		for (var i = 0; i < lines.length; i++) {
			var str = lines[i],
				index = -1;

			if (inResults) {
				if ((index = str.indexOf('[INFO]')) != -1) {
					str = str.slice(index + 8).trim();
				}
				if ((index = str.indexOf('!TEST_RESULTS_STOP!')) != -1) {
					str = str.slice(0, index).trim();
					inResults = false;
					done = true; // we got the results we need, when we kill this process we'll move on
				}

				iosTestResults += str;
				if (!inResults) {
					iosTestResults = iosTestResults.trim(); // for some reason, there's a leading space that is messing with everything!
					prc.kill();
					break;
				}
			}
			else if ((index = str.indexOf('!TEST_RESULTS_START!')) != -1) {
				inResults = true;
				iosTestResults = str.substr(index + 20).trim();
			}

			// Handle when app crashes and we haven't finished tests yet! 
			if ((index = str.indexOf('-- End application log ----')) != -1) {
				prc.kill(); // quit this build...
				if (count > 3) {
					next("failed to get test results before log ended!"); // failed too many times
				} else {
					runBuild(next, count + 1); // retry
				}
			}
		}
		
	});
	prc.stderr.on('data', function (data) {
		console.log(data.toString());
	});

	prc.on('close', function (code) {
		if (done) {
			next(); // only move forward if we got results and killed the process!
		}
	});
}

function runAndroidBuild(next, count) {
	var prc,
		inResults = false,
		done = false;
	prc = spawn('titanium', ['build', '--project-dir', path.join(__dirname, 'mocha'), '--platform', 'android', '--target', 'emulator', '--no-prompt', '--no-colors']);
	prc.stdout.on('data', function (data) {
		console.log(data.toString());
		var lines = data.toString().trim().match(/^.*([\n\r]+|$)/gm);
		for (var i = 0; i < lines.length; i++) {
			var str = lines[i],
				index = -1;

			if (inResults) {
				if ((index = str.indexOf('[INFO]')) != -1) {
					str = str.slice(index + 8).trim();
				}
				if ((index = str.indexOf('!TEST_RESULTS_STOP!')) != -1) {
					str = str.slice(0, index).trim();
					inResults = false;
					done = true; // we got the results we need, when we kill this process we'll move on
				}

				androidTestResults += str;
				if (!inResults) {
					androidTestResults = androidTestResults.trim(); // for some reason, there's a leading space that is messing with everything!
					prc.kill();
					break;
				}
			}
			else if ((index = str.indexOf('!TEST_RESULTS_START!')) != -1) {
				inResults = true;
				androidTestResults = str.substr(index + 20).trim();
			}

			// Handle when app crashes and we haven't finished tests yet! 
			if ((index = str.indexOf('-- End application log ----')) != -1) {
				prc.kill(); // quit this build...
				if (count > 3) {
					next("failed to get test results before log ended!"); // failed too many times
				} else {
					runBuild(next, count + 1); // retry
				}
			}
		}
		
	});
	prc.stderr.on('data', function (data) {
		console.log(data.toString());
	});

	prc.on('close', function (code) {
		if (done) {
			next(); // only move forward if we got results and killed the process!
		}
	});
}

function parseTestResults(testResults, next) {
	if (!testResults) {
		next("Failed to retrieve any tests results!");
	} else {
		// preserve newlines, etc - use valid JSON
		testResults = testResults.replace(/\\n/g, "\\n")  
				   .replace(/\\'/g, "\\'")
				   .replace(/\\"/g, '\\"')
				   .replace(/\\&/g, "\\&")
				   .replace(/\\r/g, "\\r")
				   .replace(/\\t/g, "\\t")
				   .replace(/\\b/g, "\\b")
				   .replace(/\\f/g, "\\f");
		// remove non-printable and other non-valid JSON chars
		testResults = testResults.replace(/[\u0000-\u0019]+/g,""); 
		jsonResults = JSON.parse(testResults);
		next();
	}
}

function outputJUnitXML(jsonResults, fileName, next) {
	// We need to go through the results and separate them out into suites!
	var suites = {},
		keys = [],
		values = [],
		r = '';
	jsonResults.results.forEach(function(item) {
		var s = suites[item.suite] || {tests: [], suite: item.suite, duration: 0, passes: 0, failures: 0, start:''}; // suite name to group by
		s.tests.unshift(item);
		s.duration += item.duration;
		if (item.state == 'failed') {
			s.failures += 1;
		} else if (item.state == 'passed') {
			s.passes += 1;
		}
		suites[item.suite] = s;
	});
	keys = Object.keys(suites);
	values = keys.map(function(v) { return suites[v]; });
	var r = ejs.render('' + fs.readFileSync(path.join('.', 'junit.xml.ejs')),  { 'suites': values });

	// Write the JUnit XML to a file
	fs.writeFileSync(path.join('..', '..', 'dist', fileName), r);
	next();
}

/**
 * Installs the SDK from master branch, generates a Titanium mobile project
 * , sets up the project, copies unit tests into it from ti_mocha_tests,
 * and then runs the project in a ios simulator and android emulator which will run the mocha unit tests. The test results are piped to
 * the CLi, which takes them and generates a JUnit test result XML report for the Jenkins build machine.
 */
function test(callback) {
	async.series([
/*		function (next) {
			// If this is already installed we don't re-install, thankfully
			console.log("Installing SDK from master branch");
			installSDK(next);
		},*/
		function (next) {
			getSDKInstallDir(next);
		},
		function (next) {
			console.log("Generating project");
			generateProject(next);

		},
		function (next) {
			console.log("Adding properties for tiapp.xml");
			addTiAppProperties(next);
		},
		function (next) {
			console.log("Copying test scripts into project");
			copyMochaAssets(next);
		},
		function (next) {
			console.log("Launching ios test project in simulator");
			runIOSBuild(next, 1);
		},
		function (next) {
			parseTestResults(iosTestResults, next);
		}/*,
		function (next) {
			outputJUnitXML(jsonResults,'ios_junit_report.xml', next);

		},
		function (next) {
			console.log("Launching android test project in emulator");
			runAndroidBuild(next, 1);
		},
		function (next) {
			parseTestResults(androidTestResults, next);
		},
		function (next) {
			outputJUnitXML(jsonResults, 'android_junit_report.xml', next);
		}*/		
	], function(err) {
		callback(err, iosTestResults);
	});

}

// public API
exports.test = test;

// When run as single script.
if (module.id === ".") {
	test(function (err, results) {
		console.log('kiatoto got it here');
		console.log(results);
		if (err) {
			console.error(err.toString().red);
			process.exit(1);
		} else {
			process.exit(1);
		}
	});
}

