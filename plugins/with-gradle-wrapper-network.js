const fs = require('fs');
const path = require('path');
const { withDangerousMod } = require('expo/config-plugins');

/** Keep Gradle distribution downloads reliable after every Expo prebuild. */
module.exports = function withGradleWrapperNetwork(config) {
  return withDangerousMod(config, [
    'android',
    async (androidConfig) => {
      const propertiesPath = path.join(
        androidConfig.modRequest.platformProjectRoot,
        'gradle',
        'wrapper',
        'gradle-wrapper.properties',
      );
      let properties = fs.readFileSync(propertiesPath, 'utf8');
      properties = setProperty(properties, 'networkTimeout', '120000');
      properties = setProperty(properties, 'retries', '3');
      properties = setProperty(properties, 'retryBackOffMs', '1000');
      fs.writeFileSync(propertiesPath, properties);

      const gradlePropertiesPath = path.join(
        androidConfig.modRequest.platformProjectRoot,
        'gradle.properties',
      );
      let gradleProperties = fs.readFileSync(gradlePropertiesPath, 'utf8');
      gradleProperties = setProperty(
        gradleProperties,
        'systemProp.org.gradle.internal.http.connectionTimeout',
        '120000',
      );
      gradleProperties = setProperty(
        gradleProperties,
        'systemProp.org.gradle.internal.http.socketTimeout',
        '120000',
      );
      fs.writeFileSync(gradlePropertiesPath, gradleProperties);
      return androidConfig;
    },
  ]);
};

function setProperty(contents, name, value) {
  const line = `${name}=${value}`;
  const expression = new RegExp(`^${name}=.*$`, 'm');
  return expression.test(contents)
    ? contents.replace(expression, line)
    : `${contents.trimEnd()}\n${line}\n`;
}
