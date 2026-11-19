#!/usr/bin/perl
# 린코 빚갚기 런처 배포 목록(distribution.json) 만들기.
#   perl tools/build_distro.pl <바탕 distribution.json>
# 바탕 = 차카데미(또는 무한의 탑) 런처의 distribution.json. 거기서 린코에 필요한 것만 그대로 가져오고
# (Fabric, Fabric API·Sodium·Iris·한글 패치, 쉐이더와 그 설정), 이 브랜치의 mods/ · files/ · pack/ 을 더한다.
# 가져온 파일 주소는 원래 배포 주소를 그대로 쓴다.
use strict; use warnings;
use JSON::PP; use Digest::MD5; use Digest::SHA;

my $src = shift or die "usage: perl tools/build_distro.pl <base distribution.json>\n";
my $RAW = 'https://raw.githubusercontent.com/zzunwoooo/chacademi_Result/refs/heads/claude/build-launcher-distro';
my $ADDRESS = q(211.53.196.235:25690);   # 건축 서버, 자동 접속

open my $fh, '<', $src or die "$src: $!"; local $/; my $base = decode_json(<$fh>); close $fh;
my $from = $base->{servers}[0];

my @KEEP_MODS  = qw(fabric-api sodium iris koreanpatch);
my @KEEP_FILES = ('shaderpacks/ComplementaryUnbound_r5.5.1.zip', 'shaderpacks/ComplementaryUnbound_r5.5.1.zip.txt',
                  'config/iris.properties', 'config/iris-excluded.json', 'config/sodium-options.json',
                  'config/sodium-mixins.properties', 'config/koreanpatch.json5');
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

# 린코 전용 모드 (mods/*.jar)
for my $jar (sort glob('mods/*.jar')) {
    my ($base) = $jar =~ m{mods/(.+)\.jar$};
    my ($art, $ver) = $base =~ /^(.+?)-(\d[\w.\-]*)$/ or die "모드 파일 이름은 <이름>-<버전>.jar: $jar\n";
    push @mods, { id => "kr.chacademi.build:$art:$ver\@jar", name => "mods/$base.jar", type => 'FabricMod',
                  artifact => { sums($jar), url => "$RAW/mods/$base.jar" } };
}
# Axiom 본체 (Modrinth 원본 주소 그대로)
push @mods, { id => q(com.moulberry:axiom:5.4.2@jar), name => q(mods/Axiom-5.4.2-for-MC1.21.4.jar), type => q(FabricMod),
              artifact => { MD5 => q(6f33952b92125e98999537e5a5628e0f), size => 47072174,
                            url => q(https://cdn.modrinth.com/data/N6n5dqoA/versions/ItmR0IzD/Axiom-5.4.2-for-MC1.21.4.jar) } };
# 린코 전용 설정 (files/ 아래 경로 그대로 게임 폴더에. 처음 한 번만 넣고 그 뒤로는 유저 것을 지킨다)
for my $f (sort grep { -f } glob('files/* files/*/* files/*/*/*')) {
    (my $rel = $f) =~ s{^files/}{};
    push @files, { id => $rel, name => $rel, type => 'File', updatePolicy => 'seed',
                   artifact => { sums($f), path => $rel, url => "$RAW/files/$rel" } };
}

# 리소스팩 (pack/generated.zip = 서버의 ItemsAdder 가 iazip 으로 만든 파일: 장식·빛 벽·시설 모델 등)
# 런처가 받아서 게임 설정에 자동으로 켠다. 게임 폴더 안 이름은 런처에 고정돼 있어 그대로 따른다.
if (-f 'pack/generated.zip') {
    my $p = 'resourcepacks/chacademia-integrated-20261023.zip';
    push @files, { id => 'chacademia-integrated-20261023.zip', name => $p, type => 'File',
                   artifact => { sums('pack/generated.zip'), path => $p, url => "$RAW/pack/generated.zip" } };
}

my $server = {
    id => q(build-1.21.4), name => q(건축용 임시 런처), description => 'Minecraft 1.21.4 (Fabric)',
    icon => "$RAW/server-icon.png", version => '1.0.0', address => $ADDRESS, minecraftVersion => '1.21.4',
    mainServer => JSON::PP::true, autoconnect => JSON::PP::true,
    javaOptions => $from->{javaOptions}, modules => [@mods, @files],
};
my $out = { version => '1.0.0', rss => '', servers => [$server] };
open my $o, '>:raw', 'distribution.json' or die $!;
print $o JSON::PP->new->utf8->canonical->pretty->encode($out); close $o;
my $bytes = 0; $bytes += ($_->{artifact}{size} // 0) for @mods, @files;
printf "distribution.json: 모드·로더 %d개, 파일 %d개, 합계 %.1f MB\n", scalar(@mods), scalar(@files), $bytes / 1048576;
