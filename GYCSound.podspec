Pod::Spec.new do |spec|
  spec.name = 'GYCSound'
  spec.version = '1.0.0'
  spec.summary = 'GY CrossKit Sound Kuikly iOS native receiver'
  spec.homepage = 'https://github.com/gycrosskit/sound'
  spec.license = { :type => 'Apache-2.0', :file => 'LICENSE' }
  spec.author = { 'GY CrossKit' => 'https://github.com/gycrosskit' }
  spec.source = { :git => 'https://github.com/gycrosskit/sound.git', :tag => spec.version.to_s }
  spec.ios.deployment_target = '14.0'
  spec.swift_version = '5.9'
  spec.subspec 'Kuikly' do |kuikly|
    kuikly.source_files = 'ios/Sources/GYCSoundKuikly/**/*.swift'
    kuikly.dependency 'OpenKuiklyIOSRender', '2.29.1'
  end
end
