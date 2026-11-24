#!/usr/bin/perl
# 무협 RPG 개발 서버 런처 배포 목록(distribution.json) 만들기.  (Claude, 2026-11-24 — 레뮤 배포 목록 스크립트에서 갈라 만듦)
#   perl tools/build_distro.pl <바탕 distribution.json>
# 바탕 = 무한의 탑 런처(claude/tower-launcher-distro)의 distribution.json. 거기서 필요한 것만 그대로 가져오고
# (Fabric, Fabric API·Sodium·Iris·한글 패치·PortableVFX, 쉐이더와 그 설정, PortableVFX 메모리 설정),
# 부가 모드는 다이아 배포 목록에서, 이 브랜치의 mods/ · vfx/ · files/ 를 더한다.
# 가져온 파일 주소는 원래 배포 주소를 그대로 쓴다.
use strict; use warnings; use utf8;   # 스크립트 안 한글(서버 이름)을 글자로 읽게 — 없으면 이중 인코딩되어 런처에서 깨짐
use JSON::PP; use Digest::MD5; use Digest::SHA;

my $src = shift or die "usage: perl tools/build_distro.pl <base distribution.json>\n";
my $RAW = 'https://raw.githubusercontent.com/zzunwoooo/chacademi_Result/refs/heads/claude/wuxia-launcher-distro';
my $ADDRESS = '211.53.196.231:25820';   # 무협 RPG 개발 서버: 런처가 게임을 켜면 바로 접속

open my $fh, '<', $src or die "$src: $!"; local $/; my $base = decode_json(<$fh>); close $fh;
my $from = $base->{servers}[0];

my @KEEP_MODS  = qw(fabric-api sodium iris koreanpatch portablevfx);
my @KEEP_FILES = ('shaderpacks/ComplementaryUnbound_r5.5.1.zip', 'shaderpacks/ComplementaryUnbound_r5.5.1.zip.txt',
                  'config/iris.properties', 'config/iris-excluded.json', 'config/sodium-options.json',
                  'config/sodium-mixins.properties', 'config/koreanpatch.json5',
                  'config/portablevfx/load-limits.properties', 'config/portablevfx/bloom.properties');
my %keepFile = map { $_ => 1 } @KEEP_FILES;

sub sums {
    my ($file) = @_;
    open my $f, '<:raw', $file or die "$file: $!";
    my $md5 = Digest::MD5->new->addfile($f)->hexdigest; close $f;
    open $f, '<:raw', $file or die; my $sha = Digest::SHA->new(256)->addfile($f)->hexdigest; close $f;
    return (MD5 => $md5, SHA256 => $sha, size => -s $file);
}

my (@mods, @files, %seen);
for my $m (@{ $from->{modules} }) {
    if ($m->{type} eq 'Fabric') { $seen{fabric} = 1; push @mods, $m; next; }
    if ($m->{type} eq 'FabricMod') {
        my ($name) = $m->{id} =~ /^[^:]+:([^:]+):/;
        next unless grep { $_ eq $name } @KEEP_MODS;
        $seen{$name} = 1; delete $m->{subModules}; push @mods, $m; next;
    }
    if ($m->{type} eq 'File') {
        my $p = $m->{artifact}{path} // '';
        if ($keepFile{$p}) { $seen{$p} = 1; push @files, $m; }
    }
}
for ('fabric', @KEEP_MODS, @KEEP_FILES) { die "바탕 목록에 없음: $_\n" unless $seen{$_}; }

# 차카데미 런처의 필수 부가 모드 — 다이아 런처 목록에서 가져옴
my @EXTRA = qw(authme blurserveraddress donation journeymap keyeventmod nochatreports plasmovoice playeranimator wi_zoom);   # playeranimator = 모션 모드가 씀
{
    open my $xf, '<', '/c/ddd/distribution.json' or die "다이아 배포 목록: $!"; local $/; my $x = decode_json(<$xf>); close $xf;
    my %got;
    for my $m (@{ $x->{servers}[0]{modules} }) {
        next unless $m->{type} eq 'FabricMod';
        my ($name) = $m->{id} =~ /^[^:]+:([^:]+):/;
        next unless grep { $_ eq $name } @EXTRA;
        $got{$name} = 1; delete $m->{subModules}; push @mods, $m;
    }
    for (@EXTRA) { die "부가 모드 없음: $_\n" unless $got{$_}; }
}

# 무협 전용 모드 (mods/*.jar, 지금은 모션 모드 wuxia-motion)
for my $jar (sort glob('mods/*.jar')) {
    my ($base) = $jar =~ m{mods/(.+)\.jar$};
    my ($art, $ver) = $base =~ /^(.+?)-(\d[\w.\-]*)$/ or die "모드 파일 이름은 <이름>-<버전>.jar: $jar\n";
    push @mods, { id => "kr.wuxia:$art:$ver\@jar", name => "mods/$base.jar", type => 'FabricMod',
                  artifact => { sums($jar), url => "$RAW/mods/$base.jar" } };
}

# 무공 VFX 묶음 (vfx/<묶음>/ → 게임 폴더 config/portablevfx/effects/<묶음>/). 늘 배포본으로 맞춘다
for my $f (sort grep { -f } glob('vfx/*/* vfx/*/*/*')) {
    (my $rel = $f) =~ s{^vfx/}{config/portablevfx/effects/};
    push @files, { id => $rel, name => $rel, type => 'File',
                   artifact => { sums($f), path => $rel, url => "$RAW/$f" } };
}

# 무협 전용 설정 (files/ 아래 경로 그대로 게임 폴더에. 처음 한 번만 넣고 그 뒤로는 유저 것을 지킨다)
# options.txt 기본값을 고쳐서 이미 설치한 사람에게도 한 번 다시 넣으려면 이 판 이름을 바꾼다
my $OPTIONS_SEED = '20261124a';
for my $f (sort grep { -f } glob('files/* files/*/* files/*/*/*')) {
    (my $rel = $f) =~ s{^files/}{};
    push @files, { id => $rel, name => $rel, type => 'File', updatePolicy => 'seed',
                   ($rel eq 'options.txt' ? (seedVersion => $OPTIONS_SEED) : ()),
                   artifact => { sums($f), path => $rel, url => "$RAW/files/$rel" } };
}

# 리소스팩: 지금은 서버 ItemsAdder 가 직접 내려줌(100MB 이하). 100MB 를 넘으면 pack/generated.zip 으로 여기에 넣는다
if (-f 'pack/generated.zip') {
    my $p = 'resourcepacks/chacademia-integrated-20261023.zip';
    push @files, { id => 'chacademia-integrated-20261023.zip', name => $p, type => 'File',
                   artifact => { sums('pack/generated.zip'), path => $p, url => "$RAW/pack/generated.zip" } };
}

my $server = {
    id => 'wuxia-1.21.4', name => '무협 RPG 개발', description => 'Minecraft 1.21.4 (Fabric)',
    icon => "$RAW/server-icon.png", version => '1.0.0', address => $ADDRESS, minecraftVersion => '1.21.4',
    mainServer => JSON::PP::true, autoconnect => JSON::PP::true,
    javaOptions => $from->{javaOptions}, modules => [@mods, @files],
};
my $out = { version => '1.0.0', rss => '', servers => [$server] };
open my $o, '>:raw', 'distribution.json' or die $!;
print $o JSON::PP->new->utf8->canonical->pretty->encode($out); close $o;
my $bytes = 0; $bytes += ($_->{artifact}{size} // 0) for @mods, @files;
printf "distribution.json: 모드·로더 %d개, 파일 %d개, 합계 %.1f MB\n", scalar(@mods), scalar(@files), $bytes / 1048576;
