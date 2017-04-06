#!/usr/bin/env python
# -*- coding: utf-8 -*-

##############################################
#
# Getting Github PR information
#
##############################################
'''
工具集合
'''

import os
import sys
import urllib2
from argparse import ArgumentParser
from json import loads as jsloads
from shlex import split as shsplit
from shutil import rmtree as rmdir
from subprocess import Popen, PIPE


def fetch_api(url, token, **cus_headers):
    ''' Fetch api '''
    token = "token {0:s}".format(token)
    request = urllib2.Request(url)
    request.add_header('User-Agent', 'zzlzhang')
    request.add_header('Content-Type', 'application/json')
    request.add_header('Authorization', token)
    if 'headers' in cus_headers:
        for hder in cus_headers['headers']:
            request.add_header(hder['type'], hder['val'])

    try:
        response = urllib2.urlopen(request).read()
    except urllib2.HTTPError, e:
        raise e
        if e.code == 404:
            print("Can't not find your pull request, please confirm your PR number.")
            os.sys.exit(10)
        elif e.code == 401:
            print("Github API token authorize failed.")
            os.sys.exit(11)
        else:
            print("Undefined error found, return value: 12")
            os.sys.exit(12)
    else:
        return response


def write_context_to_file(file_name, cmds):
    msg = None
    return_code = 0
    o_file = open(file_name, 'w+')

    try:
        proc = Popen(
            args=shsplit(cmds),
            bufsize=8192,
            stdout=o_file,
            stderr=PIPE,
            close_fds=True,
        )
        std_err_msg = proc.communicate()[1]
        assert proc.returncode == 0, std_err_msg
        #if proc.returncode != 0:
        #    std_err_msg = proc.communicate()[1]
    except OSError as e:
        msg = """Subprocess execute failed
Error message:{0:s}
""".format(e.strerror)
        return_code = -1
    except Exception as e:
        msg = "{0:s}Error code: {1:d}".format(e.message, proc.returncode)
        return_code = -1
    finally:
        o_file.close()
        if return_code == 0:
            return 0
        print(msg)
        sys.exit(-1)


def get_files_for_diff(args):
    '''
    分别获取 PR 中所有所有文件的 bash 和 head 版本,
    将获取的文件在服务器中使用 diff 产生 patch, 在应用补丁到SC中
    由于AIX版本 diff 和 patch 版本过低, 所以需要到 server 上 patch 文件
    '''
    base_restore_directory = args.restore_dir
    need_apply_files = 'apply_files.txt'
    base_sha = args.base_sha
    head_sha = args.head_sha
    github_token = os.environ.get('GITHUB_TOKEN')
    assert github_token, "Github token can not be empty."

    mango_work_tree = os.environ.get('MANGO_PATH')
    assert mango_work_tree, "Mango directory can not be empty."
    git_cmd = 'git --git-dir={0:s}/.git --work-tree={0:s}'.format(mango_work_tree)

    url = 'https://api.github.com/repos/sugareps/Mango/compare/{0:s}...{1:s}'.format(
        base_sha,
        head_sha
    )

    try:
        response = fetch_api(url=url, token=github_token)
        data = jsloads(response)
    except urllib2.HTTPError as e:
        errmsg = {
            401: '{0:s}: Please check if your commit sha is correct.'.format(e.reason),
            404: '{0:s}: Please check if your token is correct.'.format(e.reason),
        }[e.code]
        if errmsg:
            print errmsg
            sys.exit(e.code)
    except ValueError as e:
        print("Wrong response value : {0:s}".format(e.message))
        sys.exit(2)

    # 创建新目录, 并进入到该目录中生成所有文件
    rmdir(base_restore_directory, ignore_errors=True)
    os.makedirs(base_restore_directory)
    os.chdir(base_restore_directory)

    file_list = open(need_apply_files, 'w+')
    #  生成PR中的文件: base 和 head 两个版本
    for pr_file in data['files']:
        # 只允许更新 sugarcrm 目录下的文件更新
        # 空的 sha 表示文件内容没有更新, 如: 修改文件权限
        if not pr_file['sha'] or not pr_file['filename'].startswith('sugarcrm/'):
            continue

        dirname = os.path.split(pr_file['filename'])[0]
        status = pr_file['status']

        if not os.path.exists(dirname):
            os.makedirs(dirname)

        base_file_name = "{0:s}.base".format(pr_file['filename'])
        head_file_name = "{0:s}.head".format(pr_file['filename'])
        base_cmd = "{0:s} show {1:s}:{2:s}".format(git_cmd, base_sha, pr_file['filename'])
        head_cmd = "{0:s} show {1:s}:{2:s}".format(git_cmd, head_sha, pr_file['filename'])

        # # status: added | modified | removed
        # added: 只有 head
        # removed: 只有 base
        # modified: 包含 base 和 head
        if status == 'added':
            # head_sha
            write_context_to_file(head_file_name, head_cmd)
        elif status == 'modified':
            # base_sha
            write_context_to_file(base_file_name, base_cmd)
            # head_sha
            write_context_to_file(head_file_name, head_cmd)
        elif status == 'removed':
            # base_sha
            write_context_to_file(base_file_name, base_cmd)

        # 写入更新文件信息
        file_list.write(pr_file['filename'])
        file_list.write('\n')

    # 循环结束后关闭文件
    file_list.close()


def get_args():
    ''' 参数解析 '''
    parser = ArgumentParser(prog="util")
    subparsers = parser.add_subparsers(prog='util')

    arg_for_diff = subparsers.add_parser('patch', help='patch help')
    arg_for_diff.add_argument('--type',
                              dest="type",
                              type=str,
                              default="get_files_for_diff",
                              help='Get bash and head files for a PR')
    arg_for_diff.add_argument(
        '--base',
        action='store',
        dest='base_sha',
        required=True,
        metavar='BASE SHA',
        help='Base sha'
    )
    arg_for_diff.add_argument(
        '--head',
        action='store',
        dest='head_sha',
        required=True,
        metavar='HEAD SHA',
        help='Head sha'
    )
    arg_for_diff.add_argument(
        '--restore-dir',
        action='store',
        dest='restore_dir',
        required=True,
        metavar='Restore path',
        help='Create a new directory to restore the different files(Pass PR number as the directory name).'
    )

    args = parser.parse_args()
    try:
        {
            'get_files_for_diff': get_files_for_diff,
        }[args.type](args)
    except KeyError:
        print(parser.print_help())
        sys.exit(-1)
    except Exception as e:
        print(e.message)
        sys.exit(-1)


get_args()
